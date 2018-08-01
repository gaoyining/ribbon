/*
*
* Copyright 2013 Netflix, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/
package com.netflix.loadbalancer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.netflix.client.ClientFactory;
import com.netflix.client.config.IClientConfig;
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicDoubleProperty;
import com.netflix.config.DynamicPropertyFactory;

/**
 * Load balancer that can avoid a zone as a whole when choosing server. 
 *<p>
 * The key metric used to measure the zone condition is Average Active Requests,
which is aggregated per rest client per zone. It is the
total outstanding requests in a zone divided by number of available targeted instances (excluding circuit breaker tripped instances).
This metric is very effective when timeout occurs slowly on a bad zone.
<p>
The  LoadBalancer will calculate and examine zone stats of all available zones. If the Average Active Requests for any zone has reached a configured threshold, this zone will be dropped from the active server list. In case more than one zone has reached the threshold, the zone with the most active requests per server will be dropped.
Once the the worst zone is dropped, a zone will be chosen among the rest with the probability proportional to its number of instances.
A server will be returned from the chosen zone with a given Rule (A Rule is a load balancing strategy, for example {@link AvailabilityFilteringRule})
For each request, the steps above will be repeated. That is to say, each zone related load balancing decisions are made at real time with the up-to-date statistics aiding the choice.


 选择服务器时可以避免整个区域的负载均衡器。

 用于衡量区域条件的关键指标是平均活动请求，
 每个区域的每个休息客户端聚合。 它是
 区域中的未完成请求总数除以可用目标实例的数量（不包括断路器跳闸实例）。
 当在坏区域上缓慢发生超时时，此度量标准非常有效。

 LoadBalancer将计算并检查所有可用区域的区域统计信息。 如果任何区域的平均活动请求已达到配置的阈值，则将从活动服务器列表中删除此区域。 如果多个区域已达到阈值，则将删除每个服务器具有最多活动请求的区域。
 一旦最差区域被丢弃，将在其余区域中选择一个区域，其概率与其实例数量成比例。
 将使用给定规则从所选区域返回服务器（规则是负载平衡策略，例如{@link AvailabilityFilteringRule}）
 对于每个请求，将重复上述步骤。 也就是说，每个区域相关的负载平衡决策都是实时进行的，其中包含有助于选择的最新统计数据。

 * @author awang
 *
 * @param <T>
 */
public class ZoneAwareLoadBalancer<T extends Server> extends DynamicServerListLoadBalancer<T> {

    private ConcurrentHashMap<String, BaseLoadBalancer> balancers = new ConcurrentHashMap<String, BaseLoadBalancer>();
    
    private static final Logger logger = LoggerFactory.getLogger(ZoneAwareLoadBalancer.class);
            
    private volatile DynamicDoubleProperty triggeringLoad;

    private volatile DynamicDoubleProperty triggeringBlackoutPercentage; 

    private static final DynamicBooleanProperty ENABLED = DynamicPropertyFactory.getInstance().getBooleanProperty("ZoneAwareNIWSDiscoveryLoadBalancer.enabled", true);
            
    void setUpServerList(List<Server> upServerList) {
        this.upServerList = upServerList;
    }
    
    public ZoneAwareLoadBalancer() {
        super();
    }

    @Deprecated
    public ZoneAwareLoadBalancer(IClientConfig clientConfig, IRule rule,
            IPing ping, ServerList<T> serverList, ServerListFilter<T> filter) {
        super(clientConfig, rule, ping, serverList, filter);
    }

    public ZoneAwareLoadBalancer(IClientConfig clientConfig, IRule rule,
                                 IPing ping, ServerList<T> serverList, ServerListFilter<T> filter,
                                 ServerListUpdater serverListUpdater) {
        super(clientConfig, rule, ping, serverList, filter, serverListUpdater);
    }

    public ZoneAwareLoadBalancer(IClientConfig niwsClientConfig) {
        super(niwsClientConfig);
    }

    @Override
    protected void setServerListForZones(Map<String, List<Server>> zoneServersMap) {
        super.setServerListForZones(zoneServersMap);
        if (balancers == null) {
            balancers = new ConcurrentHashMap<String, BaseLoadBalancer>();
        }
        for (Map.Entry<String, List<Server>> entry: zoneServersMap.entrySet()) {
        	String zone = entry.getKey().toLowerCase();
            getLoadBalancer(zone).setServersList(entry.getValue());
        }
        // check if there is any zone that no longer has a server
        // and set the list to empty so that the zone related metrics does not
        // contain stale data
        for (Map.Entry<String, BaseLoadBalancer> existingLBEntry: balancers.entrySet()) {
            if (!zoneServersMap.keySet().contains(existingLBEntry.getKey())) {
                existingLBEntry.getValue().setServersList(Collections.emptyList());
            }
        }
    }

    /**
     * 选择服务器
     * @param key
     * @return
     */
    @Override
    public Server chooseServer(Object key) {
        if (!ENABLED.get() || getLoadBalancerStats().getAvailableZones().size() <= 1) {
            logger.debug("Zone aware logic disabled or there is only one zone");
            return super.chooseServer(key);
        }
        Server server = null;
        try {
            LoadBalancerStats lbStats = getLoadBalancerStats();
            Map<String, ZoneSnapshot> zoneSnapshot = ZoneAvoidanceRule.createSnapshot(lbStats);
            logger.debug("Zone snapshots: {}", zoneSnapshot);
            if (triggeringLoad == null) {
                triggeringLoad = DynamicPropertyFactory.getInstance().getDoubleProperty(
                        "ZoneAwareNIWSDiscoveryLoadBalancer." + this.getName() + ".triggeringLoadPerServerThreshold", 0.2d);
            }

            if (triggeringBlackoutPercentage == null) {
                triggeringBlackoutPercentage = DynamicPropertyFactory.getInstance().getDoubleProperty(
                        "ZoneAwareNIWSDiscoveryLoadBalancer." + this.getName() + ".avoidZoneWithBlackoutPercetage", 0.99999d);
            }
            Set<String> availableZones = ZoneAvoidanceRule.getAvailableZones(zoneSnapshot, triggeringLoad.get(), triggeringBlackoutPercentage.get());
            logger.debug("Available zones: {}", availableZones);
            if (availableZones != null &&  availableZones.size() < zoneSnapshot.keySet().size()) {
                String zone = ZoneAvoidanceRule.randomChooseZone(zoneSnapshot, availableZones);
                logger.debug("Zone chosen: {}", zone);
                if (zone != null) {
                    BaseLoadBalancer zoneLoadBalancer = getLoadBalancer(zone);
                    server = zoneLoadBalancer.chooseServer(key);
                }
            }
        } catch (Exception e) {
            logger.error("Error choosing server using zone aware logic for load balancer={}", name, e);
        }
        if (server != null) {
            return server;
        } else {
            logger.debug("Zone avoidance logic is not invoked.");
            return super.chooseServer(key);
        }
    }
     
    @VisibleForTesting
    BaseLoadBalancer getLoadBalancer(String zone) {
        zone = zone.toLowerCase();
        BaseLoadBalancer loadBalancer = balancers.get(zone);
        if (loadBalancer == null) {
        	// We need to create rule object for load balancer for each zone
        	IRule rule = cloneRule(this.getRule());
            loadBalancer = new BaseLoadBalancer(this.getName() + "_" + zone, rule, this.getLoadBalancerStats());
            BaseLoadBalancer prev = balancers.putIfAbsent(zone, loadBalancer);
            if (prev != null) {
            	loadBalancer = prev;
            }
        } 
        return loadBalancer;        
    }

    private IRule cloneRule(IRule toClone) {
    	IRule rule;
    	if (toClone == null) {
    		rule = new AvailabilityFilteringRule();
    	} else {
    		String ruleClass = toClone.getClass().getName();        		
    		try {
				rule = (IRule) ClientFactory.instantiateInstanceWithClientConfig(ruleClass, this.getClientConfig());
			} catch (Exception e) {
				throw new RuntimeException("Unexpected exception creating rule for ZoneAwareLoadBalancer", e);
			}
    	}
    	return rule;
    }
    
       
    @Override
    public void setRule(IRule rule) {
        super.setRule(rule);
        if (balancers != null) {
            for (String zone: balancers.keySet()) {
                balancers.get(zone).setRule(cloneRule(rule));
            }
        }
    }
}

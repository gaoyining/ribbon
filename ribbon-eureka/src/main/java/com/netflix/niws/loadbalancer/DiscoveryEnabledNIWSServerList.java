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
package com.netflix.niws.loadbalancer;

import java.util.ArrayList;
import java.util.List;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import com.netflix.client.config.IClientConfigKey.Keys;
import com.netflix.config.ConfigurationManager;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.loadbalancer.AbstractServerList;
import com.netflix.loadbalancer.DynamicServerListLoadBalancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Provider;

/**
 * The server list class that fetches the server information from Eureka client. ServerList is used by
 * {@link DynamicServerListLoadBalancer} to get server list dynamically.
 *
 * 从Eureka客户端获取服务器信息的服务器列表类。 {@link DynamicServerListLoadBalancer}使用ServerList动态获取服务器列表。
 *
 * @author stonse
 *
 */
public class DiscoveryEnabledNIWSServerList extends AbstractServerList<DiscoveryEnabledServer>{

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryEnabledNIWSServerList.class);

    String clientName;
    /**
     * 虚拟vip
     */
    String vipAddresses;
    /**
     * 是否安全
     */
    boolean isSecure = false;

    /**
     * 是否设置优先虚拟ip服务器
     */
    boolean prioritizeVipAddressBasedServers = true;

    String datacenter;
    String targetRegion;

    int overridePort = DefaultClientConfigImpl.DEFAULT_PORT;
    /**
     * 是否覆盖端口
     */
    boolean shouldUseOverridePort = false;
    /**
     * 是否用ip和地址
     */
    boolean shouldUseIpAddr = false;

    private final Provider<EurekaClient> eurekaClientProvider;

    /**
     * @deprecated use {@link #DiscoveryEnabledNIWSServerList(String)}
     * or {@link #DiscoveryEnabledNIWSServerList(IClientConfig)}
     */
    @Deprecated
    public DiscoveryEnabledNIWSServerList() {
        this.eurekaClientProvider = new LegacyEurekaClientProvider();
    }

    /**
     * @deprecated
     * use {@link #DiscoveryEnabledNIWSServerList(String, javax.inject.Provider)}
     * @param vipAddresses
     */
    @Deprecated
    public DiscoveryEnabledNIWSServerList(String vipAddresses) {
        this(vipAddresses, new LegacyEurekaClientProvider());
    }

    /**
     * @deprecated
     * use {@link #DiscoveryEnabledNIWSServerList(com.netflix.client.config.IClientConfig, javax.inject.Provider)}
     * @param clientConfig
     */
    @Deprecated
    public DiscoveryEnabledNIWSServerList(IClientConfig clientConfig) {
        this(clientConfig, new LegacyEurekaClientProvider());
    }

    public DiscoveryEnabledNIWSServerList(String vipAddresses, Provider<EurekaClient> eurekaClientProvider) {
        this(createClientConfig(vipAddresses), eurekaClientProvider);
    }

    public DiscoveryEnabledNIWSServerList(IClientConfig clientConfig, Provider<EurekaClient> eurekaClientProvider) {
        this.eurekaClientProvider = eurekaClientProvider;
        initWithNiwsConfig(clientConfig);
    }

    /**
     * 初始化相关配置
     * @param clientConfig
     */
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        // 客户端名称
        clientName = clientConfig.getClientName();
        // 虚拟ip
        vipAddresses = clientConfig.resolveDeploymentContextbasedVipAddresses();
        if (vipAddresses == null &&
                ConfigurationManager.getConfigInstance().getBoolean("DiscoveryEnabledNIWSServerList.failFastOnNullVip", true)) {
            throw new NullPointerException("VIP address for client " + clientName + " is null");
        }
        isSecure = Boolean.parseBoolean(""+clientConfig.getProperty(CommonClientConfigKey.IsSecure, "false"));
        prioritizeVipAddressBasedServers = Boolean.parseBoolean(""+clientConfig.getProperty(CommonClientConfigKey.PrioritizeVipAddressBasedServers, prioritizeVipAddressBasedServers));
        datacenter = ConfigurationManager.getDeploymentContext().getDeploymentDatacenter();
        targetRegion = (String) clientConfig.getProperty(CommonClientConfigKey.TargetRegion);

        shouldUseIpAddr = clientConfig.getPropertyAsBoolean(CommonClientConfigKey.UseIPAddrForServer, DefaultClientConfigImpl.DEFAULT_USEIPADDRESS_FOR_SERVER);

        // override client configuration and use client-defined port
        // 覆盖客户端配置并使用客户端定义的端口
        if(clientConfig.getPropertyAsBoolean(CommonClientConfigKey.ForceClientPortConfiguration, false)){

            if(isSecure){

                if(clientConfig.containsProperty(CommonClientConfigKey.SecurePort)){

                    overridePort = clientConfig.getPropertyAsInteger(CommonClientConfigKey.SecurePort, DefaultClientConfigImpl.DEFAULT_PORT);
                    shouldUseOverridePort = true;

                }else{
                    logger.warn(clientName + " set to force client port but no secure port is set, so ignoring");
                }
            }else{

                if(clientConfig.containsProperty(CommonClientConfigKey.Port)){

                    overridePort = clientConfig.getPropertyAsInteger(CommonClientConfigKey.Port, DefaultClientConfigImpl.DEFAULT_PORT);
                    shouldUseOverridePort = true;

                }else{
                    logger.warn(clientName + " set to force client port but no port is set, so ignoring");
                }
            }
        }
    }

    @Override
    public List<DiscoveryEnabledServer> getInitialListOfServers(){
        return obtainServersViaDiscovery();
    }

    @Override
    public List<DiscoveryEnabledServer> getUpdatedListOfServers(){
        return obtainServersViaDiscovery();
    }

    /**
     * 通过Discovery获取服务器
     * @return
     */
    private List<DiscoveryEnabledServer> obtainServersViaDiscovery() {
        List<DiscoveryEnabledServer> serverList = new ArrayList<DiscoveryEnabledServer>();

        if (eurekaClientProvider == null || eurekaClientProvider.get() == null) {
            logger.warn("EurekaClient has not been initialized yet, returning an empty list");
            return new ArrayList<DiscoveryEnabledServer>();
        }

        EurekaClient eurekaClient = eurekaClientProvider.get();
        if (vipAddresses!=null){
            for (String vipAddress : vipAddresses.split(",")) {
                // if targetRegion is null, it will be interpreted as the same region of client
                // 如果targetRegion为null，它将被解释为客户端的同一区域
                List<InstanceInfo> listOfInstanceInfo = eurekaClient.getInstancesByVipAddress(vipAddress, isSecure, targetRegion);
                for (InstanceInfo ii : listOfInstanceInfo) {
                    if (ii.getStatus().equals(InstanceStatus.UP)) {

                        if(shouldUseOverridePort){
                            if(logger.isDebugEnabled()){
                                logger.debug("Overriding port on client name: " + clientName + " to " + overridePort);
                            }

                            // copy is necessary since the InstanceInfo builder just uses the original reference,
                            // and we don't want to corrupt the global eureka copy of the object which may be
                            // used by other clients in our system
                            // 复制是必要的，因为InstanceInfo构建器只使用原始引用，
                            // 我们不想破坏可能的对象的全局eureka副本
                            // 由我们系统中的其他客户使用
                            InstanceInfo copy = new InstanceInfo(ii);

                            if(isSecure){
                                ii = new InstanceInfo.Builder(copy).setSecurePort(overridePort).build();
                            }else{
                                ii = new InstanceInfo.Builder(copy).setPort(overridePort).build();
                            }
                        }

                        DiscoveryEnabledServer des = createServer(ii, isSecure, shouldUseIpAddr);
                        serverList.add(des);
                    }
                }
                if (serverList.size()>0 && prioritizeVipAddressBasedServers){
                    break; // if the current vipAddress has servers, we dont use subsequent vipAddress based servers
                }
            }
        }
        return serverList;
    }

    protected DiscoveryEnabledServer createServer(final InstanceInfo instanceInfo, boolean useSecurePort, boolean useIpAddr) {
        DiscoveryEnabledServer server = new DiscoveryEnabledServer(instanceInfo, useSecurePort, useIpAddr);

        // Get availabilty zone for this instance.
        EurekaClientConfig clientConfig = eurekaClientProvider.get().getEurekaClientConfig();
        String[] availZones = clientConfig.getAvailabilityZones(clientConfig.getRegion());
        String instanceZone = InstanceInfo.getZone(availZones, instanceInfo);
        server.setZone(instanceZone);

        return server;
    }

    public String getVipAddresses() {
        return vipAddresses;
    }

    public void setVipAddresses(String vipAddresses) {
        this.vipAddresses = vipAddresses;
    }

    public String toString(){
        StringBuilder sb = new StringBuilder("DiscoveryEnabledNIWSServerList:");
        sb.append("; clientName:").append(clientName);
        sb.append("; Effective vipAddresses:").append(vipAddresses);
        sb.append("; isSecure:").append(isSecure);
        sb.append("; datacenter:").append(datacenter);
        return sb.toString();
    }


    private static IClientConfig createClientConfig(String vipAddresses) {
        IClientConfig clientConfig = DefaultClientConfigImpl.getClientConfigWithDefaultValues();
        clientConfig.set(Keys.DeploymentContextBasedVipAddresses, vipAddresses);
        return clientConfig;
    }
}
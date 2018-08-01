package com.netflix.niws.loadbalancer;

import com.netflix.discovery.DiscoveryManager;
import com.netflix.discovery.EurekaClient;

import javax.inject.Provider;

/**
 * A legacy class to provide eurekaclient via static singletons
 *
 * 通过静态单例提供eurekaclient的传统类
 */
class LegacyEurekaClientProvider implements Provider<EurekaClient> {

    private volatile EurekaClient eurekaClient;

    @Override
    public synchronized EurekaClient get() {
        if (eurekaClient == null) {
            eurekaClient = DiscoveryManager.getInstance().getDiscoveryClient();
        }

        return eurekaClient;
    }
}
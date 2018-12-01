package com.wmt.framework.zookeeper;

import com.wmt.framework.model.InvokerService;
import com.wmt.framework.model.ProviderService;

import java.util.List;
import java.util.Map;

/**
 * 消费端注册中心接口
 * Created by weimiantong on 18/12/1.
 */
public interface IRegisterCenter4Invoker {
    /**
     * 消费端初始化服务提供者信息本地缓存
     *
     * @param remoteAppKey
     * @param groupName
     */
    void initProviderMap(String remoteAppKey, String groupName);

    /**
     * 消费端获取服务提供者信息
     *
     * @return
     */
    Map<String, List<ProviderService>> getServiceMetaDataMap4Consume();

    /**
     * 消费端将消费者信息注册到zk对应的节点下
     */
    void registerInvoker(final InvokerService invoker);

}

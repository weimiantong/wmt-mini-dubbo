package com.wmt.framework.revoker;

import avro.shaded.com.google.common.collect.Lists;
import avro.shaded.com.google.common.collect.Maps;
import avro.shaded.com.google.common.collect.Sets;
import com.wmt.framework.helper.PropertyConfigeHelper;
import com.wmt.framework.model.AresResponse;
import com.wmt.framework.model.ProviderService;
import com.wmt.framework.serialization.NettyDecoderHandler;
import com.wmt.framework.serialization.NettyEncoderHandler;
import com.wmt.framework.serialization.common.SerializeType;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Channel 连接池工厂类 针对每个服务提供者地址, 预先生成了一个保存Channel的阻塞队列 实现对Netty的Channel 通道进行复用
 * Created by weimiantong on 18/12/2.
 */
public class NettyChannelPoolFactory {
    private static final Logger logger = LoggerFactory.getLogger(NettyChannelPoolFactory.class);
    private static final NettyChannelPoolFactory channelPoolFactory = new NettyChannelPoolFactory();

    /**
     * Key为服务提供者地址,value为Netty Channel阻塞队列
     */
    private static final Map<InetSocketAddress, ArrayBlockingQueue<Channel>> channelPoolMap = Maps.newConcurrentMap();

    /**
     * 初始化Netty Channel阻塞队列的长度,该值为可配置信息
     */
    private static final int channelConnectSize = PropertyConfigeHelper.getChannelConnectSize();

    /**
     * 初始化序列化协议类型,该值为可配置信息
     */
    private static final SerializeType serializeType = PropertyConfigeHelper.getSerializeType();

    /**
     * 服务提供者列表
     */
    private List<ProviderService> serviceMetaDataList = Lists.newArrayList();

    private NettyChannelPoolFactory() {

    }

    /**
     * 初始化Netty channel 连接队列Map
     * @param providerMap
     */
    public void initChannelPoolFactory(Map<String, List<ProviderService>> providerMap) {
        //将服务提供者信息存入serviceMetaDataList列表
        Collection<List<ProviderService>> collectionServiceMetaDataList = providerMap.values();
        for (List<ProviderService> serviceMetaDataModels : collectionServiceMetaDataList) {
            if (CollectionUtils.isEmpty(serviceMetaDataModels)) {
                continue;
            }
            serviceMetaDataModels.addAll(serviceMetaDataModels);
        }

        //获取服务提供者地址列表
        Set<InetSocketAddress> socketAddressSet = Sets.newHashSet();
        for (ProviderService serviceMetaData : serviceMetaDataList) {
            String serviceIP = serviceMetaData.getServerIp();
            int servicePort = serviceMetaData.getServerPort();

            InetSocketAddress socketAddress = new InetSocketAddress(serviceIP, servicePort);
            socketAddressSet.add(socketAddress);
        }

        //根据服务提供者地址列表初始化Channel阻塞队列,并以地址为Key,地址对应的Channel阻塞队列为value,存入channelPoolMap
        for (InetSocketAddress socketAddress : socketAddressSet) {
            try {
                int realChannelConnectSize = 0;
                while (realChannelConnectSize < channelConnectSize) {
                    Channel channel = null;
                    while (channel == null) {
                        //若channel 不存在 则注册新的Netty Channel
                        channel = registerChannel(socketAddress);
                    }
                    //计数器,初始化的时候存入阻塞队列的Netty Channel 个数不超过 channelConnectSize
                    realChannelConnectSize++;

                    //将新注册的NettyChannel 存入阻塞队列channelArrayBlockingQueue
                    ArrayBlockingQueue<Channel> channelArrayBlockingQueue = channelPoolMap.get(socketAddress);
                    if (channelArrayBlockingQueue == null) {
                        channelArrayBlockingQueue = new ArrayBlockingQueue<Channel>(channelConnectSize);
                        channelPoolMap.put(socketAddress, channelArrayBlockingQueue);
                    }
                    channelArrayBlockingQueue.offer(channel);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    /**
     * 根据服务提供者地址获取对应的Netty Channel 阻塞队列
     *
     * @param socketAddress
     * @return
     */
    public ArrayBlockingQueue<Channel> acquire(InetSocketAddress socketAddress) {
        return channelPoolMap.get(socketAddress);
    }

    /**
     *  Channel使用完毕之后,回收到阻塞队列arrayBlockingQueue
     *
     * @param arrayBlockingQueue
     * @param channel
     * @param inetSocketAddress
     */
    public void release(ArrayBlockingQueue<Channel> arrayBlockingQueue, Channel channel, InetSocketAddress inetSocketAddress) {
        if (arrayBlockingQueue == null) {
            return;
        }
        //回收之前先检查channel是否可用, 不可用的话, 重新注册一个, 放入阻塞队列
        if (channel == null || !channel.isOpen() || !channel.isOpen() || !channel.isWritable()) {
            if (channel != null) {
                channel.deregister().syncUninterruptibly().awaitUninterruptibly();
                channel.closeFuture().syncUninterruptibly().awaitUninterruptibly();
            }
            Channel newChannel = null;
            while (newChannel == null) {
                logger.debug("---------register new Channel-------------");
                newChannel = registerChannel(inetSocketAddress);
            }
            arrayBlockingQueue.offer(newChannel);
            return;
        }
        arrayBlockingQueue.offer(channel);
    }

    /**
     * 为服务提供者地址socketAddress注册新的Channel
     *
     * @param socketAddress
     * @return
     */
    private Channel registerChannel(InetSocketAddress socketAddress) {
        try {
            EventLoopGroup group = new NioEventLoopGroup(10);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.remoteAddress(socketAddress);

            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception{
                            //注册Netty编码器
                            ch.pipeline().addLast(new NettyEncoderHandler(serializeType));
                            //注册Netty解码器
                            ch.pipeline().addLast(new NettyDecoderHandler(AresResponse.class, serializeType));
                            //注册客户端业务逻辑处理handler
                            ch.pipeline().addLast(new NettyClientInvokeHandler());
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }


    public static void channelPoolFactoryInstance() {
    }
}

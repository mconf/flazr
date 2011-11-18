/*
 * Flazr <http://flazr.com> Copyright (C) 2009  Peter Thomas.
 *
 * This file is part of Flazr.
 *
 * Flazr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Flazr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Flazr.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.flazr.rtmp.proxy;

import com.flazr.rtmp.RtmpConfig;
import com.flazr.util.StopMonitor;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RtmpProxy {

    private static final Logger logger = LoggerFactory.getLogger(RtmpProxy.class);

    static {
        RtmpConfig.configureProxy();
        ALL_CHANNELS = new DefaultChannelGroup("rtmp-proxy");
    }

    protected static final ChannelGroup ALL_CHANNELS;

    public static void main(String[] args) throws Exception {        

        Executor executor = Executors.newCachedThreadPool();
        ChannelFactory factory = new NioServerSocketChannelFactory(executor, executor);
        ServerBootstrap sb = new ServerBootstrap(factory);
        ClientSocketChannelFactory cf = new NioClientSocketChannelFactory(executor, executor);
        sb.setPipelineFactory(new ProxyPipelineFactory(cf,
                RtmpConfig.PROXY_REMOTE_HOST, RtmpConfig.PROXY_REMOTE_PORT));
        InetSocketAddress socketAddress = new InetSocketAddress(RtmpConfig.PROXY_PORT);
        sb.bind(socketAddress);
        logger.info("proxy server started, listening on {}", socketAddress);

        Thread monitor = new StopMonitor(RtmpConfig.PROXY_STOP_PORT);
        monitor.start();
        monitor.join();

        ChannelGroupFuture future = ALL_CHANNELS.close();
        logger.info("closing channels");
        future.awaitUninterruptibly();
        logger.info("releasing resources");
        factory.releaseExternalResources();
        logger.info("server stopped");

    }

}

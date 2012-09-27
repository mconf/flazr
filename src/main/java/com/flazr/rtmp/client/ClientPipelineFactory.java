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

package com.flazr.rtmp.client;

import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;

import com.flazr.rtmp.RtmpDecoder;
import com.flazr.rtmp.RtmpEncoder;

public class ClientPipelineFactory implements ChannelPipelineFactory {

    private final ClientOptions options;

    public ClientPipelineFactory(final ClientOptions options) {
        this.options = options;
    }

    @Override
    public ChannelPipeline getPipeline() {
        final ChannelPipeline pipeline = Channels.pipeline();
        pipeline.addLast("handshaker", new ClientHandshakeHandler(options));
        pipeline.addLast("decoder", new RtmpDecoder());
        pipeline.addLast("encoder", new RtmpEncoder());
//        if(options.getLoad() == 1) {
//            pipeline.addLast("executor", new ExecutionHandler(
//                    new OrderedMemoryAwareThreadPoolExecutor(16, 1048576, 1048576)));
//        }
        pipeline.addLast("handler", new ClientHandler(options));
        return pipeline;
    }

}

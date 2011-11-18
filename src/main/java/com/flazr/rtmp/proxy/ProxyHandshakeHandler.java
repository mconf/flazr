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

import com.flazr.rtmp.RtmpDecoder;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelPipelineCoverage("one")
public class ProxyHandshakeHandler extends SimpleChannelUpstreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProxyHandshakeHandler.class);
    
    private int bytesWritten;
    private boolean handshakeDone;

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent e) throws Exception {
        final ChannelBuffer in = (ChannelBuffer) e.getMessage();
        bytesWritten += in.readableBytes();
        if(!handshakeDone && bytesWritten >= 3073) {
            final int remaining = bytesWritten - 3073;
            if(remaining > 0) {
                Channels.fireMessageReceived(ctx, in.readBytes(remaining));
            }
            handshakeDone = true;
            logger.debug("bytes written {}, handshake complete, switching pipeline", bytesWritten);
            ctx.getPipeline().addFirst("encoder", new ProxyEncoder());
            ctx.getPipeline().addFirst("decoder", new RtmpDecoder());
            ctx.getPipeline().remove(this);
        }        
        super.messageReceived(ctx, e);
    }
    
}

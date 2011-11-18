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

package com.flazr.rtmp;

import com.flazr.rtmp.RtmpDecoder.DecoderState;
import com.flazr.rtmp.message.ChunkSize;
import com.flazr.rtmp.message.Control;
import com.flazr.rtmp.message.MessageType;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.replay.ReplayingDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RtmpDecoder extends ReplayingDecoder<DecoderState> {

    private static final Logger logger = LoggerFactory.getLogger(RtmpDecoder.class);

    public static enum DecoderState {        
        GET_HEADER,
        GET_PAYLOAD
    }

    public RtmpDecoder() {
        super(DecoderState.GET_HEADER);                
    }
    
    private RtmpHeader header;
    private int channelId;
    private ChannelBuffer payload;
    private int chunkSize = 128;

    private final RtmpHeader[] incompleteHeaders = new RtmpHeader[RtmpHeader.MAX_CHANNEL_ID];
    private final ChannelBuffer[] incompletePayloads = new ChannelBuffer[RtmpHeader.MAX_CHANNEL_ID];
    private final RtmpHeader[] completedHeaders = new RtmpHeader[RtmpHeader.MAX_CHANNEL_ID];

    @Override
    protected Object decode(final ChannelHandlerContext ctx, final Channel channel, final ChannelBuffer in, final DecoderState state) {
        switch(state) {            
            case GET_HEADER:
                header = new RtmpHeader(in, incompleteHeaders);
                channelId = header.getChannelId();
                if(incompletePayloads[channelId] == null) { // new chunk stream
                    incompleteHeaders[channelId] = header;
                    incompletePayloads[channelId] = ChannelBuffers.buffer(header.getSize());
                }
                payload = incompletePayloads[channelId];
                checkpoint(DecoderState.GET_PAYLOAD);
            case GET_PAYLOAD:              
                final byte[] bytes = new byte[Math.min(payload.writableBytes(), chunkSize)];
                in.readBytes(bytes);
                payload.writeBytes(bytes);                
                checkpoint(DecoderState.GET_HEADER);
                if(payload.writable()) { // more chunks remain
                    return null;
                }
                incompletePayloads[channelId] = null;
                final RtmpHeader prevHeader = completedHeaders[channelId];                
                if (!header.isLarge()) {
                    header.setTime(prevHeader.getTime() + header.getDeltaTime());
                }
                final RtmpMessage message = MessageType.decode(header, payload);
                if(logger.isDebugEnabled()) {
                	// don't print millions of PING_REQUEST
                	if (message.getHeader().getMessageType() != MessageType.CONTROL || ((Control) message).getType() != Control.Type.PING_REQUEST)
                		logger.debug("<< {}", message);
                }
                payload = null;
                if(header.isChunkSize()) {
                    final ChunkSize csMessage = (ChunkSize) message;
                    logger.debug("decoder new chunk size: {}", csMessage);
                    chunkSize = csMessage.getChunkSize();
                }
                completedHeaders[channelId] = header;
                return message;
            default:               
                throw new RuntimeException("unexpected decoder state: " + state);
        }
        
    }

}

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

package com.flazr.io.flv;

import com.flazr.io.BufferReader;
import com.flazr.rtmp.message.MessageType;
import com.flazr.rtmp.RtmpHeader;
import com.flazr.rtmp.RtmpMessage;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlvAtom implements RtmpMessage {

    private static final Logger logger = LoggerFactory.getLogger(FlvAtom.class);

    private final RtmpHeader header;
    private ChannelBuffer data;    

    public static ChannelBuffer flvHeader() {
        final ChannelBuffer out = ChannelBuffers.buffer(13);
        out.writeByte((byte) 0x46); // F
        out.writeByte((byte) 0x4C); // L
        out.writeByte((byte) 0x56); // V
        out.writeByte((byte) 0x01); // version
        out.writeByte((byte) 0x05); // flags: audio + video
        out.writeInt(0x09); // header size = 9
        out.writeInt(0); // previous tag size, here = 0
        return out;
    }

    public FlvAtom(final ChannelBuffer in) {
        header = readHeader(in);
        data = in.readBytes(header.getSize());
        in.skipBytes(4); // prev offset
    }

    public FlvAtom(final BufferReader in) {
        header = readHeader(in.read(11));
        data = in.read(header.getSize());        
        in.position(in.position() + 4); // prev offset
    }

    public FlvAtom(final MessageType messageType, final int time, final ChannelBuffer in) {
        header = new RtmpHeader(messageType, time, in.readableBytes());
        data = in;
    }

    public ChannelBuffer write() {        
        final ChannelBuffer out = ChannelBuffers.buffer(15 + header.getSize());
        out.writeByte((byte) header.getMessageType().intValue());
        out.writeMedium(header.getSize());
        out.writeMedium(header.getTime());
        out.writeInt(0); // 4 bytes of zeros (reserved)
        out.writeBytes(data);
        out.writeInt(header.getSize() + 11); // previous tag size
        return out;
    }

    public static RtmpHeader readHeader(final ChannelBuffer in) {
        final MessageType messageType = MessageType.valueToEnum(in.readByte());
        final int size = in.readMedium();
        final int time = in.readMedium();
        in.skipBytes(4); // 0 - reserved
        return new RtmpHeader(messageType, time, size);
    }

    //============================ RtmpMessage =================================

    @Override
    public RtmpHeader getHeader() {
        return header;
    }

    public ChannelBuffer getData() {
        return data;
    }

    @Override
    public ChannelBuffer encode() {
        return data;
    }

    @Override
    public void decode(final ChannelBuffer in) {
        data = in;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(header);
        sb.append(" data: ").append(data);        
        return sb.toString();
    }

}

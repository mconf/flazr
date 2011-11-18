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

package com.flazr.rtmp.message;

import java.math.BigInteger;

import com.flazr.rtmp.RtmpHeader;
import com.flazr.util.Utils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;

public class Video extends DataMessage {

    public static final int H263VIDEOPACKET = 2;
    public static final int SCREENVIDEOPACKET = 3;
    public static final int ON2VP6 = 4;
    public static final int KEYFRAME = 1;
    public static final int INTERFRAME = 2;
    public static final int DISPOSABLEINTERFRAME = 3;
    
    private int width = -1;
    private int height = -1;
    
    @Override
    public boolean isConfig() { // TODO now hard coded for avc1
        return data.readableBytes() > 3 && data.getInt(0) == 0x17000000;
    }

    public Video(final RtmpHeader header, final ChannelBuffer in) {
        super(header, in);
    }

    public Video(final byte[] ... bytes) {
        super(bytes);
    }
    
    public Video(final int time, final byte[] videoData, final int length){
    	header.setTime(time);
    	data = ChannelBuffers.wrappedBuffer(videoData, 0, length);
    	header.setSize(data.readableBytes());
    }

    public Video(final int time, final byte[] prefix, final int compositionOffset, final byte[] videoData) {
        header.setTime(time);
        data = ChannelBuffers.wrappedBuffer(prefix, Utils.toInt24(compositionOffset), videoData);
        header.setSize(data.readableBytes());
    }

    public Video(final int time, final ChannelBuffer in) {
        super(time, in);
    }

    public static Video empty() {
        Video empty = new Video();
        empty.data = ChannelBuffers.wrappedBuffer(new byte[2]);
        return empty;
    }

    @Override
    public MessageType getMessageType() {
        return MessageType.VIDEO;
    }

	public int getCodec() {
		return (data.getByte(0) & 0x0f) >> 0;
	}

	public int getFrameType() {
		return (data.getByte(0) & 0xf0) >> 4;
	}
	
	// read binary string from existing byte[]
    public String readBinaryString(byte[] mpb, int start, int len){

        byte[] buf = new byte[len];
        System.arraycopy(mpb,start,buf,0,len);

        return new BigInteger(buf).toString(2);

    } //readBinaryString()

    public int bit2uint(char[] bits){

        int uint = 0;

        for(int i=0;i<bits.length;i++){
            if(bits[i] == '1'){
                uint += Math.pow(2, (bits.length -i -1));
            }
        }

        return uint;

    }//bit2uint
    
    private String padBitSequence(String bitSrc){

        String bitSeq = bitSrc;
        int pad = 72 - bitSeq.length();

        if(pad > 0){
            for(int i=0;i<pad;i++){
                bitSeq = "0" + bitSeq;
            }
        }

        return bitSeq;

    }//padBitSequence()
    
    private int findWidth(String bits, int hwCheck){

        int width = 0;

        switch(hwCheck){

            case 0:
                width = bit2uint(bits.substring(33,41).toCharArray());
                break;

            case 1:
                width = bit2uint(bits.substring(33,49).toCharArray());
                break;

            case 2:
                width = 352;
                break;

            case 3:
                width = 176;
                break;

            case 4:
                width = 128;
                break;

            case 5:
                width = 320;
                break;

            case 6:
                width = 160;
                break;

        }

        return width;

    }//getWidth()

    
    private int findHeight(String bits, int hwCheck){

        int height = 0;

        switch(hwCheck){

            case 0:
                height = bit2uint(bits.substring(41,49).toCharArray());
                break;

            case 1:
                height = bit2uint(bits.substring(49,65).toCharArray());
                break;

            case 2:
                height = 288;
                break;

            case 3:
                height = 144;
                break;

            case 4:
                height = 96;
                break;

            case 5:
                height = 240;
                break;

            case 6:
                height = 120;
                break;

        }

        return height;

    }//findHeight()
    
    private void solveWidthAndHeight() {
		String bits = padBitSequence(readBinaryString(data.toByteBuffer().array(), 1, 9));
		
		if (getCodec() == H263VIDEOPACKET) {
			int hwCheck = bit2uint(bits.substring(30,33).toCharArray());
			width = findWidth(bits, hwCheck);
			height = findHeight(bits, hwCheck);
		} else if (getCodec() == SCREENVIDEOPACKET) {
			width = bit2uint(bits.substring(4,16).toCharArray());
			height = bit2uint(bits.substring(16,28).toCharArray());
		}
    }
    
	public int getWidth() {
		if (width == -1)
			solveWidthAndHeight();
		
		return width;
	}
	
	public int getHeight() {
		if (height == -1)
			solveWidthAndHeight();
		
		return height;
	}

	public byte[] getBody() {
		return data.toByteBuffer().array();
	}

}

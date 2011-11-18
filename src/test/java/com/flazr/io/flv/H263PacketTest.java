package com.flazr.io.flv;

import com.flazr.util.Utils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import static org.junit.Assert.*;
import org.junit.Test;

public class H263PacketTest {

    @Test
    public void testParseH263Header() {
        ChannelBuffer in = ChannelBuffers.wrappedBuffer(
                Utils.fromHex("00008400814000f0343f"));
        H263Packet packet = new H263Packet(in, 0);
        assertEquals(640, packet.getWidth());
        assertEquals(480, packet.getHeight());
    }

}

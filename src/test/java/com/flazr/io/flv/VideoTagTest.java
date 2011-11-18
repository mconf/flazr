package com.flazr.io.flv;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoTagTest {

    @Test
    public void testParseByte() {
        byte byteValue = 0x12;
        VideoTag tag = new VideoTag(byteValue);
        assertTrue(tag.isKeyFrame());
        assertEquals(VideoTag.CodecType.H263, tag.getCodecType());
    }

}

package com.flazr.io.flv;

import static org.junit.Assert.*;
import org.junit.Test;

public class AudioTagTest {

    @Test
    public void testParseMp3() {
        byte byteValue = 0x2a;
        AudioTag tag = new AudioTag(byteValue);
        assertEquals(AudioTag.CodecType.MP3, tag.getCodecType());
        assertEquals(AudioTag.SampleRate.KHZ_22, tag.getSampleRate());
        assertTrue(tag.isSampleSize16Bit());
        assertFalse(tag.isStereo());
    }

    @Test
    public void testParseAac() {
        byte byteValue = (byte) 0xaf;
        AudioTag tag = new AudioTag(byteValue);
        assertEquals(AudioTag.CodecType.AAC, tag.getCodecType());
        assertEquals(AudioTag.SampleRate.KHZ_44, tag.getSampleRate());
        assertTrue(tag.isSampleSize16Bit());
        assertTrue(tag.isStereo());
    }

}

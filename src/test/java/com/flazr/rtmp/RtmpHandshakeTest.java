package com.flazr.rtmp;

import static org.junit.Assert.*;
import com.flazr.util.Utils;
import org.junit.Test;

public class RtmpHandshakeTest {

    @Test
    public void testResolveValidationType() {
        byte[] versionBytes = Utils.fromHex("80000302");
        assertEquals(2, RtmpHandshake.getValidationTypeForClientVersion(versionBytes));
    }

}

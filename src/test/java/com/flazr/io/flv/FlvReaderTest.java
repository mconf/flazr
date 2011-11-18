package com.flazr.io.flv;

import static org.junit.Assert.*;

import com.flazr.rtmp.RtmpMessage;
import com.flazr.rtmp.message.Audio;
import com.flazr.rtmp.message.MessageType;
import com.flazr.rtmp.message.MetadataAmf0;
import com.flazr.rtmp.message.Video;
import com.flazr.util.Utils;
import java.io.File;
import org.junit.Test;

public class FlvReaderTest {

    private static final String FILE_PATH = "../temp";
    private static final String FILE_NAME = FILE_PATH + "/test.flv";

    private void writeFile(final boolean withMetadata) {
        File temp = new File(FILE_PATH);
        if(!temp.exists()) {
            temp.mkdir();
        }
        FlvWriter writer = new FlvWriter(FILE_NAME);
        if(withMetadata) {
            writer.write(new MetadataAmf0("onMetaData"));
        }
        writer.write(new Audio(Utils.fromHex("00000000")));
        writer.write(new Video(Utils.fromHex("00000000")));
        writer.write(new Audio(Utils.fromHex("00000000")));
        writer.write(new Video(Utils.fromHex("00000000")));
        writer.close();
    }

    @Test
    public void testRandomAccessOfMetadataAtom() {
        writeFile(true);
        FlvReader reader = new FlvReader(FILE_NAME);
        RtmpMessage message = reader.getMetadata();
        assertEquals(message.getHeader().getMessageType(), MessageType.METADATA_AMF0);
        reader.close();
    }

    @Test
    public void testFlvWithouMetadata() {
        writeFile(false);
        FlvReader reader = new FlvReader(FILE_NAME);
        RtmpMessage message = reader.getMetadata();
        assertEquals(message.getHeader().getMessageType(), MessageType.METADATA_AMF0);
        reader.close();
    }

    @Test
    public void testReadBackwards() {
        writeFile(true);
        FlvReader reader = new FlvReader(FILE_NAME);
        RtmpMessage m1 = reader.next();        
        assertEquals(m1.encode(), reader.prev().encode());
        assertFalse(reader.hasPrev()); // we are at beginning again
        reader.next();
        RtmpMessage m2  = reader.next();
        assertEquals(m2.encode(), reader.prev().encode());
        assertTrue(reader.hasPrev());
        reader.close();
    }

}

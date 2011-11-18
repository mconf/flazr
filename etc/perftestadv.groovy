import com.flazr.*import org.apache.mina.common.ByteBuffer
def url = "rtmpe://localhost/vod/mp4:sample1_150kbps.f4v"
// example of advanced performance testing of a flash server
// implementing the OutputWriter interface allows you to write event
// handlers for when the session gets closed, etc.

class LoggingWriter implements OutputWriter {

    def startTime = System.currentTimeMillis()        

    void close() { 
        def elapsedTime = System.currentTimeMillis() - startTime
        println '*** elapsed time: ' +  elapsedTime
    }
    
    void write(Packet packet) { }
    
    void writeFlvData(ByteBuffer buffer) { }

}

for(i in 0..9) {

    def session = new RtmpSession(url, null)        
    
    session.playDuration = 10000        
    
    session.outputWriter = new LoggingWriter()

    RtmpClient.connect session      
    
}


import com.flazr.*def host = 'localhost'def app = 'oflaDemo'def playParam = 'IronMan.flv'
// example of performance testing of a flash server
// a null filename will skip writing to a file, but stats will be logged

for(i in 0..9) {

    def session = new RtmpSession(host, 1935, app, playParam, null)        

    RtmpClient.connect session      
    
}


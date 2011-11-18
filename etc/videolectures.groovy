import com.flazr.*def host = 'oxy.videolectures.net'def app = 'video'def playParam = '2008/active/iswc08_karlsruhe/swcbtc/iswc08_swcbtc_01'def saveAs = 'test.flv'def session = new RtmpSession(host, 1935, app, playParam, saveAs)
// start seek time in milliseconds
// optional, default is 0
session.playStart = 20000

// duration in milliseconds
// optional, default is until end of stream
session.playDuration = 20000
RtmpClient.connect session

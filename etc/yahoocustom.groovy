import com.flazr.*

def videoId = "201620741"

def url = "http://video.music.yahoo.com/ver/268.0/process/getPlaylistFOP.php?node_id=v" \
        + videoId + "&tech=flash&bitrate=58&mode=&lg=Lox6nE093DOEBHJ_XTuPaP&vidH=326&vidW=576&rd=new.music.yahoo.com&lang=us&tf=controls&eventid=1301797&tk=";

def xml = Utils.getOverHttp(url)
println xml

def data = new XmlSlurper().parseText(xml)def stream = data.'SEQUENCE-ITEM'[1].STREAMdef tcUrl = stream.@APP.text()def host = stream.@SERVER.text()def queryString = stream.@QUERYSTRING.text()def path = stream.@PATH.text().substring(1)def app = tcUrl.substring(tcUrl.lastIndexOf('/') + 1)def playParam = path.substring(0, path.lastIndexOf('.')) + '?' + queryStringprintln 'tcUrl: ' + tcUrl       println 'queryString: ' + queryStringprintln 'path: ' + pathprintln 'playParam: ' + playParamprintln 'app: ' + appdef mysession = new RtmpSession(host, 1935, app, playParam, 'test.flv')params = mysession.connectParamsparams.flashVer = 'WIN 10,0,12,36'params.swfUrl = 'http://d.yimg.com/cosmos.bcst.yahoo.com/ver/268.0/embedflv/swf/fop.swf'params.tcUrl = tcUrlparams.pageUrl = 'http://new.music.yahoo.com/videos/Beyonc/Single-Ladies-(Put-A-Ring-On-It)--201620741'params.audioCodecs = 3191params.remove 'objectEncoding'println paramsdef handler = { Object[] args -> invoke = args[0]; session = args[1];     resultFor = session.resultFor(invoke)           switch (resultFor) {        case "connect":             packet = Packet.serverBw(0x2625A0)            packet.header.time = 2436539            session.send packet            session.send new Invoke("createStream", 3)                      break        case "createStream":                        streamId = invoke.lastArgAsInt            println 'using streamId: ' + streamId            session.send Packet.ping(3, 1, 0)            play = new Invoke(streamId, "play", 8, null, playParam)            play.time = 2335            session.send play               break        default:             println "not handling result for: " + resultFor    }   } as InvokeResultHandlermysession.invokeResultHandler = handlerRtmpClient.connect mysession

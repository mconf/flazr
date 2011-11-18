import com.flazr.*

def videoId = "201620741"

def url = "http://video.music.yahoo.com/ver/268.0/process/getPlaylistFOP.php?node_id=v" \
        + videoId + "&tech=flash&bitrate=5000&eventid=1301797";             

def xml = Utils.getOverHttp(url)println xmldef data = new XmlSlurper().parseText(xml)def stream = data.'SEQUENCE-ITEM'[1].STREAMdef tcUrl = stream.@APP.text()def host = stream.@SERVER.text()def queryString = stream.@QUERYSTRING.text()def path = stream.@PATH.text().substring(1)def app = tcUrl.substring(tcUrl.lastIndexOf('/') + 1)def playParam = path.substring(0, path.lastIndexOf('.')) + '?' + queryStringprintln 'queryString: ' + queryString + '\n'println 'path: ' + path + '\n'println 'playParam: ' + playParam + '\n'println 'app: ' + app + '\n'def session = new RtmpSession(host, 1935, app, playParam, 'test.flv')RtmpClient.connect session

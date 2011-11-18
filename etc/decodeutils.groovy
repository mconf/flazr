import groovy.swing.SwingBuilder
import javax.swing.JFrame
import java.awt.GridBagConstraints as GBC
import java.net.URLDecoder
import com.flazr.*

def swing = new SwingBuilder()

def inputText
def outputText
def htmlRadio
def rtmpRadio

def frame = swing.frame(title: 'Decode Utils', defaultCloseOperation: JFrame.EXIT_ON_CLOSE) {

    lookAndFeel('system')    
    gridBagLayout()
  
    def c = gbc(insets: [5, 5, 5, 5], anchor: GBC.WEST)      
    
    panel(constraints: c, border: etchedBorder()) {
        label(text: 'Enter text to decode:')
        def group = buttonGroup();
        htmlRadio = radioButton(text: "HTML / URL escaped", buttonGroup: group, selected: true);
        rtmpRadio = radioButton(text: "RTMP / AMF", buttonGroup: group);        
    }
            
    c.gridy = 1    
    
    scrollPane(constraints: c) {
        inputText = textArea(rows: 10, columns: 80)
    }
    
    c.gridy = 2
    
    button(
        text: 'Decode',
        actionPerformed: { 
            if(htmlRadio.selected) {
                outputText.text = URLDecoder.decode(inputText.text, 'UTF-8') 
            } else {
                def packet = Utils.hexToPacket(inputText.text)
                println packet
                switch(packet.header.packetType) {
                    case Packet.Type.INVOKE:
                        def invoke = new Invoke()
                        invoke.decode(packet)
                        outputText.text = invoke.toString() 
                        break
                    case Packet.Type.NOTIFY:
                        def notify = new AmfObject()
                        notify.decode(packet.data, false)
                        outputText.text = notify.toString()
                        break
                    default: 
                        outputText.text = packet.toString()                
                }
                
            }
        },
        constraints: c
    )    
    
    c.gridy = 3
    
    scrollPane(constraints: c) {
        outputText = textArea(rows: 10, columns: 80)
    }    
    
}

frame.pack()
frame.locationRelativeTo = null
frame.show()

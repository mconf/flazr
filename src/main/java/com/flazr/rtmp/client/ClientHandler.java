/*
 * Flazr <http://flazr.com> Copyright (C) 2009  Peter Thomas.
 *
 * This file is part of Flazr.
 *
 * Flazr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Flazr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Flazr.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.flazr.rtmp.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.red5.server.api.so.IClientSharedObject;
import org.red5.server.so.ClientSharedObject;
import org.red5.server.so.SharedObjectMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.io.flv.FlvWriter;
import com.flazr.rtmp.LoopedReader;
import com.flazr.rtmp.RtmpMessage;
import com.flazr.rtmp.RtmpPublisher;
import com.flazr.rtmp.RtmpReader;
import com.flazr.rtmp.RtmpWriter;
import com.flazr.rtmp.message.BytesRead;
import com.flazr.rtmp.message.ChunkSize;
import com.flazr.rtmp.message.Command;
import com.flazr.rtmp.message.Control;
import com.flazr.rtmp.message.Metadata;
import com.flazr.rtmp.message.SetPeerBw;
import com.flazr.rtmp.message.WindowAckSize;
import com.flazr.util.ChannelUtils;
import com.flazr.util.Utils;

@SuppressWarnings("deprecation")
@ChannelPipelineCoverage("one")
public class ClientHandler extends SimpleChannelUpstreamHandler {

    protected static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private int transactionId = 1;
    protected Map<Integer, String> transactionToCommandMap;
    protected ClientOptions options;
    protected byte[] swfvBytes;

	/**
	 * Shared objects map
	 */
	private volatile ConcurrentMap<String, ClientSharedObject> sharedObjects = new ConcurrentHashMap<String, ClientSharedObject>();

    protected RtmpWriter writer;

    protected int bytesReadWindow = 2500000;
    protected long bytesRead;
    protected long bytesReadLastSent;    
    protected int bytesWrittenWindow = 2500000;
    protected boolean closeChannelWhenStreamStopped = true;
    
    public RtmpPublisher publisher;
    public int streamId;    

    private Map<Integer, Integer> streamChannel = new HashMap<Integer, Integer>();

    protected synchronized int holdChannel(int streamId) {
    	Integer next = streamChannel.get(streamId);
    	if (next != null)
    		return next;
    	
    	if (streamChannel.isEmpty())
    		next = 8;
    	else
    		next = Collections.max(streamChannel.values()) + 1;
    	streamChannel.put(streamId, next);
		return next;
	}
    
    protected synchronized void releaseChannel(int streamId) {
    	streamChannel.remove(streamId);
    }
    
	/**
	 * Connect to client shared object.
	 * 
	 * @param name Client shared object name
	 * @param persistent SO persistence flag
	 * @return Client shared object instance
	 */
	public synchronized IClientSharedObject getSharedObject(String name, boolean persistent) {
		logger.debug("getSharedObject name: {} persistent {}", new Object[] { name, persistent });
		ClientSharedObject result = sharedObjects.get(name);
		if (result != null) {
			if (result.isPersistentObject() != persistent) {
				throw new RuntimeException("Already connected to a shared object with this name, but with different persistence.");
			}
			return result;
		}

		result = new ClientSharedObject(name, persistent);
		sharedObjects.put(name, result);
		return result;
	}

	/** {@inheritDoc} */
	protected void onSharedObject(Channel channel, SharedObjectMessage object) {
		logger.debug("onSharedObject");
		ClientSharedObject so = sharedObjects.get(object.getName());
		if (so == null) {
			logger.error("Ignoring request for non-existend SO: {}", object);
			return;
		}
		if (so.isPersistentObject() != object.isPersistent()) {
			logger.error("Ignoring request for wrong-persistent SO: {}", object);
			return;
		}
		logger.debug("Received SO request: {}", object);
		so.dispatchEvent(object);
	}

    public void setSwfvBytes(byte[] swfvBytes) {
        this.swfvBytes = swfvBytes;        
        logger.info("set swf verification bytes: {}", Utils.toHex(swfvBytes));        
    }

    public ClientHandler(ClientOptions options) {
        this.options = options;
        transactionToCommandMap = new HashMap<Integer, String>();        
    }

    public void writeCommandExpectingResult(Channel channel, Command command) {
        final int id = transactionId++;
        command.setTransactionId(id);
        transactionToCommandMap.put(id, command.getName());
        logger.info("sending command (expecting result): {}", command);
        channel.write(command);
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        logger.info("channel opened: {}", e);
        super.channelOpen(ctx, e);
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        logger.info("handshake complete, sending 'connect'");
        writeCommandExpectingResult(e.getChannel(), Command.connect(options));
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        logger.info("channel closed: {}", e);
        if(writer != null) {
            writer.close();
        }
        if(publisher != null) {
            publisher.close();
        }
        super.channelClosed(ctx, e);
    }
    
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) {
    	if(publisher != null && publisher.handle(me)) {
           	return;
        }
        final Channel channel = me.getChannel();
        final RtmpMessage message = (RtmpMessage) me.getMessage();
        switch(message.getHeader().getMessageType()) {
	        case CHUNK_SIZE: onChunkSize(channel, message); break; 
	        case CONTROL: onControl(channel, (Control) message); break;
	        case METADATA_AMF0:
	        case METADATA_AMF3: onMetadata(channel, (Metadata) message); break;
	        case AUDIO:
	        case VIDEO:
	        case AGGREGATE: onMultimedia(channel, message); break;
	        case COMMAND_AMF0:
	        case COMMAND_AMF3: onCommand(channel, (Command) message); break;
	        case BYTES_READ: onBytesRead(channel, message); break;
	        case WINDOW_ACK_SIZE: onWindowAckSize(channel, (WindowAckSize) message); break;
	        case SET_PEER_BW: onSetPeerBw(channel, (SetPeerBw) message); break;
	        case SHARED_OBJECT_AMF0:
	        case SHARED_OBJECT_AMF3: onSharedObject(channel, (SharedObjectMessage) message); break;
	        default: onUnknown(channel, message); break;
	    }
        // don't put together received messages handling and publishing stuff
//	    if(publisher != null && publisher.isStarted()) { // TODO better state machine
//	        publisher.fireNext(channel, 0);
//	    }		
	}

	protected void onUnknown(Channel channel, RtmpMessage message) {
        logger.info("ignoring rtmp message: {}", message);
	}

	protected void onSetPeerBw(Channel channel, SetPeerBw spb) {
        if(spb.getValue() != bytesWrittenWindow) {
            channel.write(new WindowAckSize(bytesWrittenWindow));
        }
	}

	protected void onWindowAckSize(Channel channel, WindowAckSize was) {
        if(was.getValue() != bytesReadWindow) {
            channel.write(SetPeerBw.dynamic(bytesReadWindow));
        }                
	}

	protected void onBytesRead(Channel channel, RtmpMessage message) {
        logger.debug("ack from server: {}", message);
	}

	protected void onCommand(Channel channel, Command command) {
        String name = command.getName();
        logger.debug("server command: {}", name);
        if(name.equals("_result")) {
            String resultFor = transactionToCommandMap.get(command.getTransactionId());
            if (resultFor == null) {
            	logger.warn("result for method without tracked transaction");
            } else {
            	onCommandResult(channel, command, resultFor);
            }
        } else if(name.equals("onStatus")) {
            @SuppressWarnings("unchecked")
			final Map<String, Object> args = (Map<String, Object>) command.getArg(0);
            onCommandStatus(channel, command, args);
        } else if(name.equals("close")) {
            logger.info("server called close, closing channel");
            channel.close();
        } else if(name.equals("_error")) {
            logger.error("closing channel, server resonded with error: {}", command);
            channel.close();
        } else {
        	onCommandCustom(channel, command, name);
        }
    }

	protected void onCommandCustom(Channel channel, Command command, String name) {
        logger.warn("ignoring server command: {}", command);
	}

	protected void onCommandStatus(Channel channel, Command command,
			Map<String, Object> args) {
        final String code = (String) args.get("code");
        final String level = (String) args.get("level");
        final String description = (String) args.get("description");
        final String application = (String) args.get("application");
        final String messageStr = level + " onStatus message, code: " + code + ", description: " + description + ", application: " + application;
        
        // http://help.adobe.com/en_US/FlashPlatform/reference/actionscript/3/flash/events/NetStatusEvent.html
        if (level.equals("status")) {
        	logger.info(messageStr);
            if (code.equals("NetStream.Publish.Start")
            		&& publisher != null && !publisher.isStarted()) {
        		logger.debug("starting the publisher after NetStream.Publish.Start");
            	publisher.start(channel, options.getStart(), options.getLength(), new ChunkSize(4096));
            } else if (code.equals("NetStream.Unpublish.Success")
            		&& publisher != null) {
                logger.info("unpublish success, closing channel");
                ChannelFuture future = channel.write(Command.closeStream(streamId));
                future.addListener(ChannelFutureListener.CLOSE);
            } else if (code.equals("NetStream.Play.Stop")) {
            	if (closeChannelWhenStreamStopped) {
                	channel.close();
            	}
            }
        } else if (level.equals("warning")) {
        	logger.warn(messageStr);
        	if (code.equals("NetStream.Play.InsufficientBW")) {
                ChannelFuture future = channel.write(Command.closeStream(streamId));
                future.addListener(ChannelFutureListener.CLOSE);
                // \TODO create a listener for insufficient bandwidth
        	}
        } else if (level.equals("error")) {
        	logger.error(messageStr);
            channel.close();
        }
	}

	protected void onCommandResult(Channel channel, Command command,
			String resultFor) {
        logger.info("result for method call: {}", resultFor);
        if (resultFor.equals("connect")) {
            writeCommandExpectingResult(channel, Command.createStream());
        } else if (resultFor.equals("createStream")) {
            streamId = ((Double) command.getArg(0)).intValue();
            logger.debug("streamId to use: {}", streamId);
            if(options.getPublishType() != null) { // TODO append, record                            
                RtmpReader reader;
                if(options.getFileToPublish() != null) {
                    reader = RtmpPublisher.getReader(options.getFileToPublish());
                } else {
                    reader = options.getReaderToPublish();
                }
                if(options.getLoop() > 1) {
                    reader = new LoopedReader(reader, options.getLoop());
                }
                // the use of useSharedTimer=true results in a memory leak on the shared timer
                // \TODO remove this option from RtmpPublisher - it probably should never use
                // a shared timer
                publisher = new RtmpPublisher(reader, streamId, options.getBuffer(), false, false) {
                    @Override protected RtmpMessage[] getStopMessages(long timePosition) {
                        return new RtmpMessage[]{Command.unpublish(streamId)};
                    }
                };                            
                channel.write(Command.publish(streamId, holdChannel(streamId), options));
                return;
            } else {
                writer = options.getWriterToSave();
                // do not create a writer if it wasn't set on the options
//                if(writer == null) {
//                    writer = new FlvWriter(options.getStart(), options.getSaveAs());
//                }
                
                ClientOptions newOptions = new ClientOptions();
                newOptions.setStreamName(options.getStreamName());
                channel.write(Command.play(streamId, newOptions));
                channel.write(Control.setBuffer(streamId, 0));
            }
        } else {
            logger.warn("un-handled server result for: {}", resultFor);
        }
    }

	protected void onMultimedia(Channel channel, RtmpMessage message) {
        if (writer != null)
        	writer.write(message);
        bytesRead += message.getHeader().getSize();
        if((bytesRead - bytesReadLastSent) > bytesReadWindow) {
            logger.debug("sending bytes read ack {}", bytesRead);
            bytesReadLastSent = bytesRead;
            channel.write(new BytesRead(bytesRead));
        }
	}

	protected void onMetadata(Channel channel, Metadata metadata) {
        if(metadata.getName().equals("onMetaData") && writer != null) {
            logger.debug("writing 'onMetaData': {}", metadata);
            writer.write(metadata);
        } else {
            logger.debug("ignoring metadata: {}", metadata);
        }
	}

	protected void onControl(Channel channel, Control control) {
		if (control.getType() != Control.Type.PING_REQUEST)
			logger.debug("control: {}", control);
        switch(control.getType()) {
            case PING_REQUEST:
                final int time = control.getTime();
                Control pong = Control.pingResponse(time);
                logger.trace("server ping: {}", time);
                logger.trace("sending ping response: {}", pong);
                if (channel.isWritable())
                	channel.write(pong);
                break;
            case SWFV_REQUEST:
                if(swfvBytes == null) {
                    logger.warn("swf verification not initialized!" 
                        + " not sending response, server likely to stop responding / disconnect");
                } else {
                    Control swfv = Control.swfvResponse(swfvBytes);
                    logger.info("sending swf verification response: {}", swfv);
                    channel.write(swfv);
                }
                break;
            case STREAM_BEGIN:
                if(publisher != null && !publisher.isStarted()) {
                    publisher.start(channel, options.getStart(),
                            options.getLength(), new ChunkSize(4096));
                    return;
                }
                if(streamId !=0) {
                    channel.write(Control.setBuffer(streamId, options.getBuffer()));
                }
                break;
            default:
                logger.debug("ignoring control message: {}", control);
        }
	}

	protected void onChunkSize(Channel channel, RtmpMessage message) {
		// handled by decoder
	}

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        ChannelUtils.exceptionCaught(e);
    }    

}

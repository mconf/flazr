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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.netty.channel.Channel;
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
public class MultiStreamHandler extends SimpleChannelUpstreamHandler {

    protected static final Logger logger = LoggerFactory.getLogger(MultiStreamHandler.class);

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
    protected int bytesWrittenWindow = 2500000;

	protected List<Stream> publishStreamList = new ArrayList<Stream>();
	protected boolean connected;    

	protected class Stream {
		public static final int WAITING_FOR_CREATE_COMMAND = 1;
		public static final int WAITING_FOR_BEGIN_COMMAND = 2;
		public static final int WAITING_FOR_CREATE_RESULT = 3;
		public static final int STARTED = 4;
		public static final int CLOSED = 0;
		
		public String streamName;
		public int streamId;
		public int state;
		public RtmpPublisher publisher;
		public RtmpReader reader;
	}
	
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

    public MultiStreamHandler(ClientOptions options) {
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
    
    public void writeCommand(Channel channel, RtmpMessage message) {
        logger.info("sending command (without expecting result): {}", message);
    	channel.write(message);
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
		for (Stream stream : publishStreamList) {
			if (stream.publisher != null)
				stream.publisher.close();
		}
        super.channelClosed(ctx, e);
    }
    
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent me) {
		boolean handledByPublisher = false;
		for (Stream stream : publishStreamList) {
			if (stream.publisher != null && stream.publisher.handle(me))
				handledByPublisher = true;
		}
		if (handledByPublisher)
			return;
		
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
        if (connected) {
	        for (Stream stream : publishStreamList) {
	        	if (stream.state == Stream.WAITING_FOR_CREATE_COMMAND) {
	                stream.state = Stream.WAITING_FOR_CREATE_RESULT;
	                writeCommandExpectingResult(channel, Command.createStream());
	        	}
	        }
        }
	}

	protected void onUnknown(Channel channel, RtmpMessage message) {
        logger.info("ignoring rtmp message: {}", message);
	}

	protected void onSetPeerBw(Channel channel, SetPeerBw spb) {
		logger.debug("set peer bandwidth: " + spb);
        if(spb.getValue() != bytesWrittenWindow) {
            writeCommand(channel, new WindowAckSize(bytesWrittenWindow));
        }
	}

	protected void onWindowAckSize(Channel channel, WindowAckSize was) {
		logger.debug("window ack size: " + was);
        if(was.getValue() != bytesReadWindow) {
            writeCommand(channel, SetPeerBw.dynamic(bytesReadWindow));
        }                
	}

	protected void onBytesRead(Channel channel, RtmpMessage message) {
//        logger.debug("ack from server: {}", message);
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
        final int streamId = ((Double) args.get("clientid")).intValue();
        Stream stream = getStreamById(streamId);
        
		logger.debug("=========================> onCommandStatus Command: " + command);
		logger.debug("=========================> streamId: " + streamId);
		logger.debug("=========================> publishStreamList.size(): " + publishStreamList.size());
		logger.debug("=========================> streamName: " + stream.streamName);
        
        // http://help.adobe.com/en_US/FlashPlatform/reference/actionscript/3/flash/events/NetStatusEvent.html
        if (level.equals("status")) {
        	logger.info(messageStr);
            if (code.equals("NetStream.Publish.Start")
            		&& stream != null && stream.publisher != null && !stream.publisher.isStarted()) {
        		logger.debug("starting the publisher after NetStream.Publish.Start");
            	stream.publisher.start(channel, options.getStart(), options.getLength(), new ChunkSize(4096));
            	stream.state = Stream.STARTED;
            } else if (code.equals("NetStream.Unpublish.Success")
            		&& stream != null && stream.publisher != null) {
                logger.info("unpublish success, closing channel");
                closeStream(channel, stream);
//                ChannelFuture future = writeCommand(channel, Command.closeStream(streamId));
//                future.addListener(ChannelFutureListener.CLOSE);
            } else if (code.equals("NetStream.Play.Stop")) {
            	closeStream(channel, stream);
//            	channel.close();
            }
        } else if (level.equals("warning")) {
        	logger.warn(messageStr);
        	if (code.equals("NetStream.Play.InsufficientBW")) {
        		closeStream(channel, stream);
//                ChannelFuture future = writeCommand(channel, Command.closeStream(streamId));
//                future.addListener(ChannelFutureListener.CLOSE);
        	}
        } else if (level.equals("error")) {
        	logger.error(messageStr);
//            channel.close();
        }
	}

	private void closeStream(Channel channel, Stream stream) {
        writeCommand(channel, Command.closeStream(stream.streamId));
        stream.state = Stream.CLOSED;
	}

	protected void onCommandResult(Channel channel, Command command,
			String resultFor) {
        logger.info("result for method call: {}", resultFor);
        if (resultFor.equals("connect")) {
        	connected = true;
        } else if (resultFor.equals("createStream")) {
        	onCommandResultCreateStream(channel, command);
        } else {
            logger.warn("un-handled server result for: {}", resultFor);
        }
    }

	protected void onCommandResultCreateStream(Channel channel, Command command) {
        final int streamId = ((Double) command.getArg(0)).intValue();
        logger.debug("streamId to use: {}", streamId);
        if(options.getPublishType() != null) { // TODO append, record        
        	
        	Stream stream = getStreamByState(Stream.WAITING_FOR_CREATE_RESULT);
        	if (stream == null) {
        		logger.error("Inconsistent state - received a create stream result, but there's no stream to publish");
        		return;
        	}
        	
            RtmpReader reader;
            if(options.getFileToPublish() != null) {
                reader = RtmpPublisher.getReader(options.getFileToPublish());
            } else {
                reader = stream.reader;
            }
            if(options.getLoop() > 1) {
                reader = new LoopedReader(reader, options.getLoop());
            }
            // \TODO check the "useSharedTimer" argument
            stream.publisher = new RtmpPublisher(reader, streamId, options.getBuffer(), false, false) {
                @Override protected RtmpMessage[] getStopMessages(long timePosition) {
                    return new RtmpMessage[]{Command.unpublish(streamId)};
                }
            };
            ClientOptions tmpOptions = new ClientOptions();
            tmpOptions.setStreamName(stream.streamName);
            tmpOptions.setPublishType(options.getPublishType());
            stream.streamId = streamId;
            stream.state = Stream.WAITING_FOR_BEGIN_COMMAND;
            
            Command publish = Command.publish(streamId, holdChannel(streamId), tmpOptions);
            stream.publisher.setChannelId(publish.getHeader().getChannelId());
            writeCommand(channel, publish);
            return;
        } else {
            writer = options.getWriterToSave();
            if(writer == null && options.getSaveAs() != null) {
                writer = new FlvWriter(options.getStart(), options.getSaveAs());
            }
            writeCommand(channel, Command.play(streamId, options));
            writeCommand(channel, Control.setBuffer(streamId, 0));
        }
    }

	protected void onMultimedia(Channel channel, RtmpMessage message) {
        if (writer != null)
        	writer.write(message);
		logger.debug("=========================> onMultimedia RtmpMessage: " + message);
//        bytesRead += message.getHeader().getSize();
//        if((bytesRead - bytesReadLastSent) > bytesReadWindow) {
//            logger.debug("sending bytes read ack {}", bytesRead);
//            bytesReadLastSent = bytesRead;
//            writeCommand(channel, new BytesRead(bytesRead));
//        }
	}

	protected void onMetadata(Channel channel, Metadata metadata) {
        if(metadata.getName().equals("onMetaData") && writer != null) {
            logger.debug("writing 'onMetaData': {}", metadata);
            writer.write(metadata);
        } else {
            logger.debug("ignoring metadata: {}", metadata);
        }
	}

	protected Stream getStreamById(int id) {
		for (Stream s : publishStreamList)
			if (s.streamId == id)
				return s;
		return null;
	}
	
	protected Stream getStreamByState(int state) {
		for (Stream s : publishStreamList)
			if (s.state == state)
				return s;
		return null;
	}
	
	protected void onControl(Channel channel, Control control) {
		if (control.getType() != Control.Type.PING_REQUEST)
			logger.debug("control: {}", control);
        switch(control.getType()) {
            case PING_REQUEST:
                final int time = control.getTime();
                Control pong = Control.pingResponse(time);
//                logger.debug("server ping: {}", time);
//                logger.debug("sending ping response: {}", pong);
                if (channel.isWritable())
                	writeCommand(channel, pong);
                break;
            case SWFV_REQUEST:
                if(swfvBytes == null) {
                    logger.warn("swf verification not initialized!" 
                        + " not sending response, server likely to stop responding / disconnect");
                } else {
                    Control swfv = Control.swfvResponse(swfvBytes);
                    logger.info("sending swf verification response: {}", swfv);
                    writeCommand(channel, swfv);
                }
                break;
            case STREAM_BEGIN:
                int streamId = control.getStreamId();
                Stream stream = getStreamById(streamId);
                if (stream != null && stream.publisher != null && !stream.publisher.isStarted()) {
                    stream.publisher.start(channel, options.getStart(),
                        options.getLength(), new ChunkSize(4096));
                    stream.state = Stream.STARTED;
                    return;
                }
                // streamId == 0 is not a real stream
                if (streamId != 0) {
                    writeCommand(channel, Control.setBuffer(streamId, options.getBuffer()));
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

	public void addPublishStream(String streamName, RtmpReader reader) {
		logger.debug("=========================> Adding a stream to the publishStreamList");
		Stream stream = new Stream();
		stream.streamName = streamName;
		stream.state = Stream.WAITING_FOR_CREATE_COMMAND;
		stream.reader = reader;
		publishStreamList.add(stream);
	}
    
}

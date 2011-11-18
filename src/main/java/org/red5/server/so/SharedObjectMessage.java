package org.red5.server.so;

/*
 * RED5 Open Source Flash Server - http://code.google.com/p/red5/
 * 
 * Copyright (c) 2006-2010 by respective authors (see below). All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or modify it under the 
 * terms of the GNU Lesser General Public License as published by the Free Software 
 * Foundation; either version 2.1 of the License, or (at your option) any later 
 * version. 
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License along 
 * with this library; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 */

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.red5.server.api.event.IEventListener;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.message.SharedObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flazr.amf.Amf0Value;
import com.flazr.rtmp.RtmpHeader;
import com.flazr.rtmp.message.AbstractMessage;
import com.flazr.rtmp.message.MessageType;

/**
 * Shared object event
 */
public class SharedObjectMessage extends AbstractMessage implements ISharedObjectMessage, IRTMPEvent {

	private static final Logger logger = LoggerFactory.getLogger(SharedObjectMessage.class);

	private static final long serialVersionUID = -8128704039659990049L;

	/**
	 * SO event name
	 */
	private String name;

	/**
	 * SO events chain
	 */
	private ConcurrentLinkedQueue<ISharedObjectEvent> events;

	/**
	 * SO version, used for synchronization purposes
	 */
	private int version;

	/**
	 * Whether SO persistent
	 */
	private boolean persistent;

	public SharedObjectMessage() {
	}

	/**
	 * Creates Shared Object event with given name, version and persistence flag
	 * 
	 * @param name Event name
	 * @param version SO version
	 * @param persistent SO persistence flag
	 */
	public SharedObjectMessage(String name, int version, boolean persistent) {
		this(null, name, version, persistent);
	}

	/**
	 * Creates Shared Object event with given listener, name, SO version and
	 * persistence flag
	 * 
	 * @param source Event listener
	 * @param name Event name
	 * @param version SO version
	 * @param persistent SO persistence flag
	 */
	public SharedObjectMessage(IEventListener source, String name, int version, boolean persistent) {
		this.name = name;
		this.version = version;
		this.persistent = persistent;
		
		this.events = new ConcurrentLinkedQueue<ISharedObjectEvent>();
	}
	
	public SharedObjectMessage(RtmpHeader header, ChannelBuffer in) {
		super(header, in);
	}

	/** {@inheritDoc} */
	public int getVersion() {
		return version;
	}

	/**
	 * Setter for version
	 * 
	 * @param version
	 *            New version
	 */
	protected void setVersion(int version) {
		this.version = version;
	}

	/** {@inheritDoc} */
	public String getName() {
		return name;
	}

	/**
	 * Setter for name
	 * 
	 * @param name
	 *            Event name
	 */
	protected void setName(String name) {
		this.name = name;
	}

	/** {@inheritDoc} */
	public boolean isPersistent() {
		return persistent;
	}

	/**
	 * Setter for persistence flag
	 * 
	 * @param persistent
	 *            Persistence flag
	 */
	protected void setIsPersistent(boolean persistent) {
		this.persistent = persistent;
	}

	/** {@inheritDoc} */
	public void addEvent(ISharedObjectEvent event) {
		events.add(event);
	}

	public void addEvents(List<ISharedObjectEvent> events) {
		this.events.addAll(events);
	}

	public void addEvents(Queue<ISharedObjectEvent> events) {
		this.events.addAll(events);
	}

	/** {@inheritDoc} */
	public ConcurrentLinkedQueue<ISharedObjectEvent> getEvents() {
		return events;
	}

	/** {@inheritDoc} */
	public void addEvent(ISharedObjectEvent.Type type, String key, Object value) {
		events.add(new SharedObjectEvent(type, key, value));
	}

	/** {@inheritDoc} */
	public void clear() {
		events.clear();
	}

	/** {@inheritDoc} */
	public boolean isEmpty() {
		return events.isEmpty();
	}

	/** {@inheritDoc} */
	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("SharedObjectMessage: ").append(name).append(" { ");
		final Iterator<ISharedObjectEvent> it = events.iterator();
		while (it.hasNext()) {
			sb.append(it.next());
			if (it.hasNext()) {
				sb.append(" , ");
			}
		}
		sb.append(" } ");
		return sb.toString();
	}

	@Override
	public MessageType getMessageType() {
		return MessageType.SHARED_OBJECT_AMF0;
	}

	/**
	 * Encode a string without the string type byte
	 * @param out
	 * @param s
	 */
	void encodeString(ChannelBuffer out, String s) {
		out.writeShort((short) s.length());
		out.writeBytes(s.getBytes());
	}
	
	@Override
	public ChannelBuffer encode() {
        ChannelBuffer out = ChannelBuffers.dynamicBuffer();
        encodeString(out, name);
        // SO version
        out.writeInt(version);
        // Encoding (this always seems to be 2 for persistent shared objects)
        out.writeInt(persistent? 2 : 0);
        // unknown field
        out.writeInt(0);
        
        int mark, len;
        
        for (ISharedObjectEvent event : events) {
            byte type = SharedObjectTypeMapping.toByte(event.getType());

            switch (event.getType()) {
				case SERVER_CONNECT:
				case SERVER_DISCONNECT:
				case CLIENT_INITIAL_DATA:
				case CLIENT_CLEAR_DATA:
					out.writeByte(type);
					out.writeInt(0);
        			break;
        			
				case SERVER_DELETE_ATTRIBUTE:
				case CLIENT_DELETE_DATA:
				case CLIENT_UPDATE_ATTRIBUTE:
					out.writeByte(type);
					mark = out.writerIndex();
					out.writeInt(0); // we will be back

					encodeString(out, event.getKey());
					len = out.writerIndex() - mark - 4;
					out.markWriterIndex();
					
					out.writerIndex(mark);
					out.writeInt(len);
					
					out.resetWriterIndex(); // for some reason, it's needed to write an integer at the end
					out.writeInt(0);
					break;
					
				case SERVER_SET_ATTRIBUTE:
				case CLIENT_UPDATE_DATA:
					if (event.getKey() == null) {
						// Update multiple attributes in one request
						Map<?, ?> initialData = (Map<?, ?>) event.getValue();
						for (Object o : initialData.keySet()) {
							
							out.writeByte(type);
							mark = out.writerIndex();
							out.writeInt(0); // we will be back
							
							String key = (String) o;
							encodeString(out, key);
							Amf0Value.encode(out, initialData.get(key));
							
							len = out.writerIndex() - mark - 4;
							out.writerIndex(mark);
							out.writeInt(len);
						}
					} else {
						out.writeByte(type);
						mark = out.writerIndex();
						out.writeInt(0); // we will be back
						
						encodeString(out, event.getKey());
						Amf0Value.encode(out, event.getValue());
						out.markWriterIndex();

						len = out.writerIndex() - mark - 4;
						out.writerIndex(mark);
						out.writeInt(len);

						out.resetWriterIndex();
						out.writeInt(0);
					}
					break;
				case CLIENT_SEND_MESSAGE:
				case SERVER_SEND_MESSAGE:
					// Send method name and value
					out.writeByte(type);
					mark = out.writerIndex();
					out.writeInt(0); // we will be back

					// Serialize name of the handler to call...
					Amf0Value.encode(out, event.getKey());
					// ...and the arguments
					for (Object arg : (List<?>) event.getValue()) {
						Amf0Value.encode(out, arg);
					}
					out.markWriterIndex();
					
					len = out.writerIndex() - mark - 4;
					out.writerIndex(mark);
					out.writeInt(len);
					
					out.resetWriterIndex();
					out.writeInt(0);
					break;

				case CLIENT_STATUS:
					out.writeByte(type);
					final String status = event.getKey();
					final String message = (String) event.getValue();
					out.writeInt(message.length() + status.length() + 4);
					encodeString(out, message);
					encodeString(out, status);
					break;

				default:
					logger.error("Unknown event " + event.getType());
					
					out.writeByte(type);
					mark = out.writerIndex();
					out.writeInt(0); // we will be back
					
					encodeString(out, event.getKey());
					Amf0Value.encode(out, event.getValue());
					
					len = out.writerIndex() - mark - 4;
					out.writerIndex(mark);
					out.writeInt(len);
					break;
        	}
        }
        return out;
	}

	/**
	 * Read a string without the string type byte
	 * @param in
	 * @return a decoded string
	 */
	String decodeString(ChannelBuffer in) {
		int length = in.readShort();
		byte[] str = new byte[length];
		in.readBytes(str);
		return new String(str);
	}
	
	@Override
	public void decode(ChannelBuffer in) {
		name = decodeString(in);
		version = in.readInt();
		persistent = in.readInt() == 2;
		in.skipBytes(4);
		
		if (events == null)
			events = new ConcurrentLinkedQueue<ISharedObjectEvent>();
		
		while (in.readableBytes() > 0) {
			ISharedObjectEvent.Type type = SharedObjectTypeMapping.toType(in.readByte());
			if (type == null) {
				in.skipBytes(in.readableBytes());
				continue;
			}
			
			String key = null;
			Object value = null;
			
			int length = in.readInt();
			if (type == ISharedObjectEvent.Type.CLIENT_STATUS) {
				// Status code
				key = decodeString(in);
				// Status level
				value = decodeString(in);
			} else if (type == ISharedObjectEvent.Type.CLIENT_UPDATE_DATA) {
				key = null;
				// Map containing new attribute values
				final Map<String, Object> map = new HashMap<String, Object>();
				final int start = in.readerIndex();
				while (in.readerIndex() - start < length) {
					String tmp = decodeString(in);
					map.put(tmp, Amf0Value.decode(in));
				}
				value = map;
			} else if (type != ISharedObjectEvent.Type.SERVER_SEND_MESSAGE && type != ISharedObjectEvent.Type.CLIENT_SEND_MESSAGE) {
				if (length > 0) {
					key = decodeString(in);
					if (length > key.length() + 2) {
						value = Amf0Value.decode(in);
					}
				}
			} else {
				final int start = in.readerIndex();
				// the "send" event seems to encode the handler name
				// as complete AMF string including the string type byte
				key = (String) Amf0Value.decode(in);

				// read parameters
				final List<Object> list = new LinkedList<Object>();
				// while loop changed for JIRA CODECS-9
				while (in.readerIndex() - start < length) {
					list.add(Amf0Value.decode(in));
				}
				value = list;
			}
			
			addEvent(type, key, value);
		}
	}
/*	
	@SuppressWarnings({ "unchecked" })
	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		super.readExternal(in);
		name = (String) in.readObject();
		version = in.readInt();
		persistent = in.readBoolean();
		Object o = in.readObject();
		if (o != null && o instanceof ConcurrentLinkedQueue) {
			events = (ConcurrentLinkedQueue<ISharedObjectEvent>) o;
		}
	}

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		super.writeExternal(out);
		out.writeObject(name);
		out.writeInt(version);
		out.writeBoolean(persistent);
		out.writeObject(events);
	}
*/

	@Override
	public byte getDataType() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public byte getSourceType() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getTimestamp() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void release() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void retain() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setHeader(RtmpHeader header) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setSource(IEventListener source) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setSourceType(byte sourceType) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setTimestamp(int timestamp) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Object getObject() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public IEventListener getSource() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Type getType() {
		return Type.SHARED_OBJECT;
	}

	@Override
	public boolean hasSource() {
		// TODO Auto-generated method stub
		return false;
	}

}

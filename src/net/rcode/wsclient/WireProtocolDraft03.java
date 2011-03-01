package net.rcode.wsclient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

/**
 * Implement WebSocket protocol draft 03 (numbering reset due to change in working group -
 * draft03 is newer than draft76).
 * <p>
 * http://tools.ietf.org/html/draft-ietf-hybi-thewebsocketprotocol-03
 * </p>
 * 
 * @author stella
 *
 */
public class WireProtocolDraft03 extends WireProtocol {
	public static final WireProtocolDraft03 INSTANCE=new WireProtocolDraft03();
	
	private WireProtocolDraft03() {
	}
	
	@Override
	public void performHandshake(WebSocket socket, URI uri, DataInputStream in,
			DataOutputStream out) throws Exception {
		socket.getRequestHeaders().put("Sec-WebSocket-Draft", "2");
		super.performHandshake(socket, uri, in, out);
	}
	
	@Override
	public void initiateClose(WebSocket socket) {
		synchronized (socket) {
			byte[] cookie=Util.getUTF8Bytes("clientclose");
			
			if (socket.getReadyState()==WebSocket.OPEN) {
				socket.setReadyState(WebSocket.CLOSING);
			}
			socket.setCloseCookie(cookie);
			socket.getTransmissionQueue().addTail(
					new Message(Message.OPCODE_CLOSE, cookie, false));
		}
	}
	
	@Override
	public Message readMessage(WebSocket socket, DataInputStream input) throws Exception {
		for (;;) {
			int header1, header2;
			try {
				header1=input.readUnsignedByte();
				header2=input.readUnsignedByte();
			} catch (EOFException e) {
				// Just go straight to close.  Not an error.
				socket.setReadyState(WebSocket.CLOSED);
				return null;
			}
			
			// Validate
			if ((header1&0x70)!=0 || (header2&0x80)!=0) {
				throw new IOException("Protocol error");
			}
			
			if ((header1&0x80)!=0) {
				// Fragment - handle specially
				throw new IOException("Fragments not yet supported");
			}
			
			int opcode=header1&0x0f;
			int length=header2;
			if (length==126) {
				// Two bytes of length follow
				length=input.readUnsignedShort();
			} else if (length==127) {
				// 8 bytes of length follow
				long longLength=input.readLong();
				if (longLength>Integer.MAX_VALUE) throw new IOException("Message length too long");
				length=(int)longLength;
			}
			
			// Read the contents
			byte[] contents=new byte[length];
			input.readFully(contents);
			
			// If its a control message, take special action
			switch (opcode) {
			case Message.OPCODE_TEXT:
			case Message.OPCODE_BINARY:
				return new Message(opcode, contents, true);
			case Message.OPCODE_PING:
				// Respond with PONG (sneak it to the head of the tx queue)
				socket.getTransmissionQueue().addHead(
						new Message(Message.OPCODE_PONG, contents, false));
				continue;
			case Message.OPCODE_PONG:
				// Currently do nothing.  Should update a timestamp or something.
				continue;
			case Message.OPCODE_CLOSE:
				byte[] closeCookie=socket.getCloseCookie();
				//System.out.println("Close message.  Cookie=" + new String(closeCookie) + ", contents=" + new String(contents));
				if (closeCookie==null || !Arrays.equals(contents, closeCookie)) {
					// This is not an ACK of our close
					// Need to send a close ACK
					socket.getTransmissionQueue().addHead(
						new Message(Message.OPCODE_CLOSE, contents, false));
					
					// The writer will close the queue when it is done.  Just wait for it.
					int expectClose=input.read();
					if (expectClose!=-1) throw new IOException("Protocol error.  Expected EOF.  Got " + expectClose);
					return null;
				} else {
					// This is an ack of a previous close we sent.  The writer has already
					// concluded.
					socket.abort();
					return null;
				}
			default:
				throw new IOException("Protocol error");
			}
		}
	}
	
	@Override
	public boolean sendMessage(WebSocket socket, DataOutputStream out, Message message) throws Exception {
		int opcode=message.getOpcode();
		byte[] data=message.getMessageData();
		if (data==null) return true;
		
		int length=data.length;
		int header1=opcode&0xf;
		int header2;
		
		// Different paths based on length
		if (length<=125) {
			// All fits in one word
			header2=length;
			out.write(header1);
			out.write(header2);
		} else if (length<=32767) {
			// 2 byte length
			header2=126;
			out.write(header1);
			out.write(header2);
			out.writeShort(length);
		} else {
			// 8 byte length
			header2=127;
			out.write(header1);
			out.write(header2);
			out.writeLong(length);
		}
		
		out.write(data);
		out.flush();
		
		// Shutdown transmission
		if (opcode==Message.OPCODE_CLOSE) {
			return false;
		}
		
		return true;
	}

}

package net.rcode.wsclient;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;


/**
 * Implementation of the so-called draft76 WebSocket specification.  This version of the spec
 * is officially expired but widely implemented.
 * <p>
 * 		http://tools.ietf.org/html/draft-hixie-thewebsocketprotocol-76
 * </p>
 * 
 * @author stella
 *
 */
public class WireProtocolDraft76 extends WireProtocol {
	public static final WireProtocolDraft76 INSTANCE=new WireProtocolDraft76();
	
	protected WireProtocolDraft76() {
	}
	
	@Override
	public Message readMessage(WebSocket socket, DataInputStream input) throws Exception {
		for (;;) {
			int frameType;
			
			try {
				frameType=input.readUnsignedByte();
			} catch (EOFException e) {
				// Just go straight to close.  Not an error.
				socket.setReadyState(WebSocket.CLOSED);
				return null;
			}
			
			if ((frameType&0x80)==0x80) {
				int length=0;
				for (;;) {
					int b=input.readUnsignedByte(),
						bv=b&0x7f;
					length=length*128 + bv;
					if ((b&0x80)!=0x80) break;
				}
				
				byte[] data=new byte[length];
				input.readFully(data);
				
				if (frameType==0xff && length==0) {
					System.out.println("Received close start handshake");
					int readyState=socket.getReadyState();
					// Close
					if (readyState==WebSocket.OPEN) {
						// Closing handshake has not yet started - start closing handshake
						Message closeMessage=new Message(0xff, new byte[0], false);
						socket.getTransmissionQueue().addHead(closeMessage);
						socket.setReadyState(WebSocket.CLOSING);
						
						// Block on reading the next byte which should just be a stream closed
						int shouldClose=input.read();
						if (shouldClose==-1) {
							socket.setReadyState(WebSocket.CLOSED);
							return null;
						} else {
							throw new IOException("Bad close handshake");
						}
					} else if (readyState!=WebSocket.CLOSED) {
						// Done here
						socket.abort();
						return null;
					}
				} else {
					return new Message(Message.OPCODE_BINARY, data, false);
				}
			} else {
				ByteArrayOutputStream accum=new ByteArrayOutputStream();
				for (;;) {
					int b=input.read();
					if (b==0xff) break;
					accum.write(b);
				}
				
				if (frameType==0) {
					Message message=new Message(Message.OPCODE_TEXT, accum.toByteArray(), true);
					return message;
				}
			}
		}
	}
	
	@Override
	public boolean sendMessage(WebSocket socket, DataOutputStream output, Message message) throws Exception {
		int opcode=message.getOpcode();
		if (opcode==Message.OPCODE_PING) return true;	// Just ignore
		
		if (opcode==0xff) {
			// Write close message
			output.write(0xff);
			output.write(0x00);
			output.flush();
			return false;
		} else {
			if (opcode!=Message.OPCODE_TEXT) {
				throw new IllegalArgumentException("Draft76 only supports text messages");
			}
			
			output.write(0);
			output.write(message.getMessageData());
			output.write(0xff);
			output.flush();
			return true;
		}
	}

	@Override
	public void initiateClose(WebSocket socket) {
		Message closeMessage=new Message(0xff, new byte[0], false);
		socket.getTransmissionQueue().addTail(closeMessage);
	}
}

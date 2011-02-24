package net.rcode.wsclient;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

public class FramingDraft76 extends Framing {
	@Override
	public Message readMessage(DataInputStream input) throws Exception {
		for (;;) {
			int frameType=input.readUnsignedByte();
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
					// Close
					// TODO: Graceful close
					return null;
				} else {
					return new Message(Message.OPCODE_BINARY, data);
				}
			} else {
				ByteArrayOutputStream accum=new ByteArrayOutputStream();
				for (;;) {
					int b=input.read();
					if (b==0xff) break;
					accum.write(b);
				}
				
				if (frameType==0) {
					Message message=new Message(Message.OPCODE_TEXT, accum.toByteArray());
					return message;
				}
			}
		}
	}
	
	@Override
	public void sendMessage(DataOutputStream output, Message message) throws Exception {
		int opcode=message.getOpcode();
		if (opcode==Message.OPCODE_PING) return;	// Just ignore
		
		if (opcode!=Message.OPCODE_TEXT) {
			throw new IllegalArgumentException("Draft76 only supports text messages");
		}
		
		output.write(0);
		output.write(message.getMessageData());
		output.write(0xff);
		output.flush();
	}
	
}

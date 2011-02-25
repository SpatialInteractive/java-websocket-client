package net.rcode.wsclient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

public class WireProtocolDraft03 extends WireProtocol {
	@Override
	public Message readMessage(WebSocket socket, DataInputStream input) throws Exception {
		// TODO Auto-generated method stub
		return super.readMessage(socket, input);
	}
	
	@Override
	public boolean sendMessage(WebSocket socket, DataOutputStream out, Message message) throws Exception {
		byte[] data=message.getMessageData();
		if (data==null) return true;
		
		int length=data.length;
		int header1=message.getOpcode()&0xf;
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
		
		System.out.println("Wrote header for message length " + length + ": " + Integer.toHexString(header1) + " " + Integer.toHexString(header2));
		System.out.println("Write data: " + Arrays.toString(data));
		
		out.write(data);
		out.flush();
		System.out.println("Message sent");
		
		return true;
	}

}

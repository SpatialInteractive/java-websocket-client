package net.rcode.wsclient;

import java.nio.charset.Charset;

public class Message {
	private static final Charset UTF8=Charset.forName("UTF8");
	
	// -- message opcodes
	public static final int OPCODE_CONTINUATION=0;
	public static final int OPCODE_CLOSE=1;
	public static final int OPCODE_PING=2;
	public static final int OPCODE_PONG=3;
	public static final int OPCODE_TEXT=4;
	public static final int OPCODE_BINARY=5;
	
	
	private int opcode;
	private byte[] messageData;
	
	public Message(int opcode, byte[] messageData) {
		this.opcode=opcode;
		this.messageData=messageData;
	}
	
	public boolean isText() {
		return opcode==OPCODE_TEXT;
	}
	
	public String getMessageText() {
		if (isText()) return new String(messageData, UTF8);
		else throw new IllegalStateException("Not text based message");
	}
	
	public int getOpcode() {
		return opcode;
	}
	
	public byte[] getMessageData() {
		return messageData;
	}
	
	@Override
	public String toString() {
		if (opcode==OPCODE_TEXT) return getMessageText();
		else if (messageData!=null ){
			StringBuilder ret=new StringBuilder();
			for (byte b: messageData) {
				ret.append(Integer.toHexString(b)).append(" ");
			}
			return ret.toString();
		} else return "";
	}
}

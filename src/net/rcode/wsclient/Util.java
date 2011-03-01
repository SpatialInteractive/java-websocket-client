package net.rcode.wsclient;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;

public class Util {
	public static final Charset UTF8=Charset.forName("UTF8");

	public static byte[] getUTF8Bytes(CharSequence s) {
		// The String.getBytes(Charset) method was not implemented until Java 6/Android 9
		// For compatibility with older runtimes, implement it here in terms of its
		// primitives
		ByteBuffer bb=UTF8.encode(CharBuffer.wrap(s));
		byte[] ret=new byte[bb.remaining()];
		bb.get(ret);
		return ret;
	}
	
	public static CharSequence fromUTF8Bytes(byte[] bytes) {
		ByteBuffer bb=ByteBuffer.wrap(bytes);
		CharBuffer charBuffer=UTF8.decode(bb);
		return charBuffer;
	}
}

package net.rcode.wsclient;

import org.junit.Test;
import static org.junit.Assert.*;

public class UtilTest {

	@Test
	public void testGetUTF8Bytes() {
		byte[] buffer=Util.getUTF8Bytes("123");
		assertEquals(3, buffer.length);
		assertEquals((byte)'1', buffer[0]);
		assertEquals((byte)'2', buffer[1]);
		assertEquals((byte)'3', buffer[2]);
	}
}

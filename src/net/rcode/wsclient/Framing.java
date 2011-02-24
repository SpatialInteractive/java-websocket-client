package net.rcode.wsclient;

import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * Base class for supporting different framing types on a websocket
 * @author stella
 *
 */
public class Framing {	
	public Message readMessage(DataInputStream input) throws Exception {
		throw new UnsupportedOperationException();
	}
	
	public void sendMessage(DataOutputStream output, Message message) throws Exception {
		throw new UnsupportedOperationException();
	}
}

package net.rcode.wsclient;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Base class for supporting different protocol and framing types on a websocket
 * @author stella
 *
 */
public class WireProtocol {
	protected static final Random random=new Random();
	protected static final Pattern VERIFY_STATUSLINE_PATTERN=Pattern.compile("^HTTP\\/[^ ]+ 101 ");

	protected WireProtocol() {
	}
	
	public Message readMessage(WebSocket socket, DataInputStream input) throws Exception {
		throw new UnsupportedOperationException();
	}
	
	public boolean sendMessage(WebSocket socket, DataOutputStream output, Message message) throws Exception {
		throw new UnsupportedOperationException();
	}
	
	public void initiateClose(WebSocket socket) {
		socket.abort();
	}
	
	public void performHandshake(WebSocket socket, URI uri, DataInputStream in, DataOutputStream out) throws Exception {
		String key1=generateKey();
		String key2=generateKey();
		byte[] quad=new byte[8];
		random.nextBytes(quad);
		
		Map<String,String> headerMap=socket.getRequestHeaders();
		headerMap.put("Connection", "Upgrade");
		headerMap.put("Upgrade", "WebSocket");
		headerMap.put("Sec-WebSocket-Key1", key1);
		headerMap.put("Sec-WebSocket-Key2", key2);
		String[] requestedProtocols=socket.getRequestedProtocols();
		if (requestedProtocols!=null && requestedProtocols.length>0) {
			StringBuilder joinedProtocol=new StringBuilder();
			for (String protocol: requestedProtocols) {
				if (joinedProtocol.length()>0) joinedProtocol.append(' ');
				joinedProtocol.append(protocol);
			}
			headerMap.put("Sec-WebSocket-Protocol", joinedProtocol.toString());
		}
		
		// Build the request
		String path=uri.getRawPath();
		if (path.length()==0) path="/";	// Deal with malformed root
		if (uri.getRawQuery()!=null) {
			path+='?' + uri.getRawQuery();
		}

		StringBuilder request=new StringBuilder(1500);
		request.append("GET ").append(path).append(" HTTP/1.1\r\n");
		for (Map.Entry<String, String> entry: headerMap.entrySet()) {
			request.append(entry.getKey());
			request.append(": ");
			request.append(entry.getValue());
			request.append("\r\n");
		}
		request.append("\r\n");
		
		//System.out.println("Sending request \n'" + request + "'");
		out.write(Util.getUTF8Bytes(request));
		//out.flush();	// Give proxys a better chance of dealing with what follows
		out.write(quad);
		out.flush();
		
		// Read the HTTP status line
		String statusLine=readLine(in);
		if (!VERIFY_STATUSLINE_PATTERN.matcher(statusLine).find())
			throw new IOException("Bad status line from server: " + statusLine);
		
		// Read each header line until we get an empty
		Map<String,String> responseHeaders=new HashMap<String, String>();
		for (;;) {
			String headerLine=readLine(in);
			if (headerLine.length()==0) break;	// End of headers
			int colonPos=headerLine.indexOf(": ");
			if (colonPos<0) {
				throw new IOException("Illegal HTTP header in response");
			}
			
			String name=headerLine.substring(0, colonPos), value=headerLine.substring(colonPos+2);
			responseHeaders.put(name.toLowerCase(), value);
		}
		
		
		socket.setResponseHeaders(responseHeaders);
		
		// Now read the handshake from the input and verify
		byte[] serverHandshake=new byte[16];
		in.readFully(serverHandshake);
		if (socket.isVerifyHandshake())
			validateHandshake(key1, key2, quad, serverHandshake);
		
		// And finally ready to go
		socket.setReadyState(WebSocket.OPEN);
	}

	/**
	 * Validate the handshake per the spec.  Really you can't make this stuff up.  Implementation
	 * of the most obscure security measures to guard access to a public park follows.
	 * 
	 * @param key1
	 * @param key2
	 * @param randomBody
	 * @param serverHandshake
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 */
	protected void validateHandshake(String key1, String key2, byte[] clientQuad,
			byte[] serverHandshake) throws IOException, NoSuchAlgorithmException {
		StringBuilder key1Numbers=new StringBuilder(key1.length());
		StringBuilder key2Numbers=new StringBuilder(key2.length());
		int key1Spaces=0, key2Spaces=0;
		
		// Scan key1 for numbers and spaces
		for (int i=0; i<key1.length(); i++) {
			char c=key1.charAt(i);
			if (c==' ') key1Spaces++;
			else if (c>='0' && c<='9') key1Numbers.append(c);
		}
		
		// Scan key2 for numbers and spaces
		for (int i=0; i<key2.length(); i++) {
			char c=key2.charAt(i);
			if (c==' ') key2Spaces++;
			else if (c>='0' && c<='9') key2Numbers.append(c);
		}
		
		int key1Int=Integer.parseInt(key1Numbers.toString()) / key1Spaces;
		int key2Int=Integer.parseInt(key2Numbers.toString()) / key2Spaces;
		
		ByteArrayOutputStream bufferOut=new ByteArrayOutputStream(16);
		DataOutputStream bufferData=new DataOutputStream(bufferOut);
		bufferData.writeInt(key1Int);
		bufferData.writeInt(key2Int);
		bufferData.write(clientQuad);
		
		byte[] buffer=bufferOut.toByteArray();
		MessageDigest digest=MessageDigest.getInstance("MD5");
		byte[] clientHandshake=digest.digest(buffer);
		
		if (!Arrays.equals(clientHandshake, serverHandshake)) {
			throw new IOException("Client and server handshake don't match:\nraw=" + Arrays.toString(buffer) + "\nclient=" + Arrays.toString(clientHandshake) + "\nserver=" + Arrays.toString(serverHandshake));
		} else {
			//System.out.println("Handshakes match");
		}
	}
	
	/**
	 * Generate a sequence of random chars
	 * @param charCount Number of misc ascii chars
	 * @param numCount Number of numeric chars
	 * @param spaceCount Number of spaces
	 * @param armor true to armor the result (add a printable ascii char to front and back)
	 * @return the result
	 */
	public static String generateKey() {
		int divisor=random.nextInt(8)+1;
		int number=(random.nextInt(1000000)+1000) * divisor;	// Make sure it evenly divides
		
		String numberStr=String.valueOf(number);
		
		StringBuilder ret=new StringBuilder(32);
		ret.append(numberStr);
		for (int i=0; i<divisor; i++) {
			// Insert one space per divisor
			int index=random.nextInt(ret.length());
			ret.insert(index, ' ');
		}
		
		for (int i=0; i<numberStr.length()/2; i++) {
			// Strew some random characters around
			int index=random.nextInt(ret.length());
			ret.insert(index, (char)(random.nextInt(122-65)+65));
		}
		
		// Finally, add a regular ascii printable at front and back
		ret.insert(0, (char)(random.nextInt(122-65)+65));
		ret.append((char)(random.nextInt(122-65)+65));

		return ret.toString();
	}
	
	/**
	 * Reads a line of bytes terminated by CR-LF and returns it without the line ending
	 * decoded as UTF-8.  It will actually accept just a naked LF and ignore the preceeding CR
	 * if present.
	 * <p>
	 * This method presumes that the stream supports mark/reset semantics.
	 * 
	 * @param in
	 * @return String
	 * @throws IOException 
	 */
	protected String readLine(InputStream in) throws IOException {
		StringBuilder ret=new StringBuilder(256);
		
		boolean verifyLf=false;
		CharsetDecoder decoder=Util.UTF8.newDecoder();
		byte[] source=new byte[256];
		char[] dest=new char[256];
		ByteBuffer sourceBuffer=ByteBuffer.wrap(source);
		CharBuffer destBuffer=CharBuffer.wrap(dest);
		for (;;) {
			in.mark(source.length);
			int r=in.read(source);
			if (r<0) {
				throw new IOException("Short line reading response.  Got: '" + new String(source, "ISO-8859-1") + "'");
			}
			sourceBuffer.clear();
			sourceBuffer.limit(r);
			
			// Scan for a line ending
			int lineEndIndex;
			for (lineEndIndex=0; lineEndIndex<r; lineEndIndex++) {
				byte b=source[lineEndIndex];
				if (b=='\n') {
					// Naked newline terminated (no CR)
					break;
				} else if (b=='\r') {
					// Treat this as an end of line but set a flag saying we should read the next
					// byte and verify LF
					verifyLf=true;
					break;
				}
			}
			
			if (lineEndIndex==r) {
				// We rolled off the buffer without finding a line ending
				// Consume all of it
				sourceBuffer.flip();
				destBuffer.clear();
				if (decoder.decode(sourceBuffer, destBuffer, false).isError()) {
					throw new IOException("Error decoding");
				}
				ret.append(dest, 0, destBuffer.position());
				continue;
			} else {
				// The line ending was in the buffer
				sourceBuffer.position(lineEndIndex);
				sourceBuffer.flip();
				destBuffer.clear();
				if (decoder.decode(sourceBuffer, destBuffer, true).isError()) {
					throw new IOException("Error decoding");
				}
				ret.append(dest, 0, destBuffer.position());
				
				// Reset the input stream and then fast forward
				in.reset();
				in.skip(lineEndIndex+1);
				break;
			}
		}
		
		// Check for dangling LF
		if (verifyLf) {
			int lf=in.read();
			if (lf!='\n') {
				throw new IOException("Malformed line.  Unmatched CR.");
			}
		}
		
		//System.out.println("READLINE: " + ret);
		return ret.toString();
	}

}

package net.rcode.wsclient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import javax.net.SocketFactory;

/**
 * Provide an implementation of the HTML5 WebSocket class:
 * http://dev.w3.org/html5/websockets/
 * <p>
 * Where feasible, we use similar conventions/names here as in the HTML5 spec for
 * familiarity.  This class manages a worker thread which actually performs all of
 * the connection management.  For simplicity, the several events are collapsed
 * down to a single event handler registration and distinguished with an event code.
 * <p>
 * This class implements thewebsocketprotocol-03
 * http://tools.ietf.org/html/draft-ietf-hybi-thewebsocketprotocol-03
 * 
 * @author stella
 *
 */
public class WebSocket {
	private static final Pattern VERIFY_STATUSLINE_PATTERN=Pattern.compile("^HTTP\\/[^ ]+ 101");
	private static final Pattern INVALID_HEADER_NAME_PATTERN=Pattern.compile("[\\r\\n\\:]");
	private static final Pattern INVALID_HEADER_VALUE_PATTERN=Pattern.compile("[\\r\\n]");
	private static final Charset UTF8=Charset.forName("UTF-8");
	private static final Random random=new Random();

	public static class Event {
		/**
		 * An EVENT_* constant
		 */
		protected int type;
		
		/**
		 * The source WebSocket
		 */
		protected WebSocket source;
		
		/**
		 * Snapshot of the readyState at the time of the event
		 */
		protected int readyState;
		
		/**
		 * On MESSAGE event types this is the message
		 */
		protected Message message;
		
		/**
		 * On error event types, this is the error
		 */
		protected Throwable error;
		
		public int getType() {
			return type;
		}

		public WebSocket getSource() {
			return source;
		}

		public int getReadyState() {
			return readyState;
		}

		public Message getMessage() {
			return message;
		}

		public Throwable getError() {
			return error;
		}
		
		public String toString() {
			StringBuilder ret=new StringBuilder();
			ret.append("<Event ").append(type).append(", readyState=").append(readyState).append(">\n");
			if (type==EVENT_ERROR && error!=null) {
				ret.append(error.getMessage());
			} else if (type==EVENT_MESSAGE) {
				ret.append(message.toString());
			}
			ret.append("</Event>");
			return ret.toString();
		}
	}
	
	public static interface EventListener {
		public void handleEvent(Event event);
	}
	
	
	// -- readyState constants
	public static final int CONNECTING=0;
	public static final int OPEN=1;
	public static final int CLOSING=2;
	public static final int CLOSED=3;

	/**
	 * Combines the open and close events and just exposes one
	 * readyState change event
	 */
	public static final int EVENT_READYSTATE=0;
	/**
	 * A message has been received.  The message field will be set.
	 */
	public static final int EVENT_MESSAGE=1;
	/**
	 * An error occurred.  If this was due to an exception, the event error
	 * field will be set.  In general, errors cause the io thread to commence
	 * shutdown.
	 */
	public static final int EVENT_ERROR=2;
	
	// -- public properties (read-only)
	private int readyState;
	private String protocol;
	private String url;
	private Map<String, String> requestHeaders;
	private Map<String, String> responseHeaders;
	private boolean verifyHandshake=true;
	
	// -- public properties (read-write)
	private NetConfig netConfig=new NetConfig();
	private Framing framing=new FramingDraft76();
	
	public synchronized int getReadyState() {
		return readyState;
	}
	protected synchronized void setReadyState(int readyState) {
		boolean hasListeners;
		synchronized(this) {
			this.readyState = readyState;
			hasListeners=listeners!=null;
		}
		
		if (hasListeners) {
			Event event=new Event();
			event.source=this;
			event.type=EVENT_READYSTATE;
			event.readyState=readyState;
			signalEvent(event);
		}
	}
	
	public synchronized String getProtocol() {
		return protocol;
	}
	protected synchronized void setProtocol(String protocol) {
		this.protocol = protocol;
	}
	
	public String getUrl() {
		return url;
	}
	
	public NetConfig getNetConfig() {
		return netConfig;
	}
	public void setNetConfig(NetConfig netConfig) {
		if (started) throw new IllegalStateException();
		this.netConfig = netConfig;
	}
	
	/**
	 * Use to disable handshake verification.  Handshake is still generated per spec but
	 * not verified
	 * @param verifyHandshake
	 */
	public void setVerifyHandshake(boolean verifyHandshake) {
		if (started) throw new IllegalStateException();
		this.verifyHandshake = verifyHandshake;
	}
	
	public boolean isVerifyHandshake() {
		return verifyHandshake;
	}
	
	public void addRequestHeader(String name, String value) {
		if (started) throw new IllegalStateException();
		if (INVALID_HEADER_NAME_PATTERN.matcher(name).find() || INVALID_HEADER_VALUE_PATTERN.matcher(value).find())
			throw new IllegalArgumentException();
		if (requestHeaders==null) requestHeaders=new HashMap<String, String>();
		requestHeaders.put(name, value);
	}
	
	// -- public constructors
	public WebSocket(String url, String... requestedProtocols) {
		this.url=url;
		this.requestedProtocols=requestedProtocols.clone();
	}
	
	// -- public api
	public void send(Message message) {
		synchronized (queue) {
			boolean wasEmpty=queue.isEmpty();
			queue.addLast(message);
			if (wasEmpty) queue.notify();
		}
	}
	
	public void send(String message) {
		send(new Message(Message.OPCODE_TEXT, message.getBytes(UTF8)));
	}
	
	public void close() throws IOException {
		socket.close();
	}
	
	public synchronized void addListener(EventListener l) {
		if (dispatchingEvent && listeners!=null) {
			// Make a defensive copy
			listeners=new ArrayList<WebSocket.EventListener>(listeners);
		}
		
		if (listeners==null) listeners=new ArrayList<WebSocket.EventListener>(2);
		listeners.add(l);
	}
	
	public synchronized void removeListener(EventListener l) {
		if (listeners!=null) {
			if (dispatchingEvent) listeners=new ArrayList<WebSocket.EventListener>(listeners);
			listeners.remove(l);
		}
	}
	
	public synchronized void removeAllListeners() {
		listeners=null;
	}
	
	/**
	 * The HTML5 implementation presumably starts after the current tick.  This base class
	 * has no threading ideas so it has an explicit start() method.  If already started
	 * does nothing.  Otherwise, starts the processing thread.  Environment specific
	 * subclasses will typically integrate with the native message loop to handle this
	 * detail for you.
	 * @throws InterruptedException 
	 */
	public void start() throws InterruptedException {
		if (started) return;
		started=true;
		readerThread=new Thread("WebSocket read " + url) {
			public void run() {
				runReader();
			}
		};
		readerThread.start();
	}
	
	private void startWriter() {
		writerThread=new Thread("WebSocket write " + url) {
			public void run() {
				runWriter();
			}
		};
		writerThread.start();
	}
	
	// -- internal implementation
	private boolean started;
	private Thread readerThread, writerThread;
	private String[] requestedProtocols;
	private boolean shutdownInitiated;
	private List<EventListener> listeners;
	private boolean dispatchingEvent;
	
	protected final void signalEvent(Event event) {
		List<EventListener> listenersCopy;
		synchronized (this) {
			dispatchingEvent=true;
			listenersCopy=listeners;
		}
		
		try {
			if (listenersCopy!=null) {
				for (int i=0; i<listenersCopy.size(); i++) {
					dispatchEvent(event, listenersCopy.get(i));
				}
			}
		} finally {
			synchronized (this) {
				dispatchingEvent=false;
			}
		}
	}
	
	protected void signalError(Throwable t) {
		t.printStackTrace();
		Event event=new Event();
		event.source=this;
		event.type=EVENT_ERROR;
		event.error=t;
		signalEvent(event);
	}
	
	protected void signalMessage(Message msg) {
		Event event=new Event();
		event.source=this;
		event.readyState=readyState;
		event.type=EVENT_MESSAGE;
		event.message=msg;
		signalEvent(event);
	}
	
	/**
	 * Dispatch an event to the given listener.  Subclasses can do custom thread
	 * marshalling by overriding this method
	 * @param event
	 * @param l
	 */
	protected void dispatchEvent(Event event, EventListener l) {
		l.handleEvent(event);
	}
	
	
	// -- IO Management.  Everything from here on runs under either the reader or writer thread
	private URI uri;
	private String hostName;
	private int port;
	private String hostHeader;
	private String path;
	private SocketFactory socketFactory;
	private Socket socket;
	private DataInputStream in;
	private DataOutputStream out;
	
	/**
	 * Message queue.  Put on end, read from front.  Synchronize on the queue
	 * and notify when transition to !empty.
	 */
	private LinkedList<Message> queue=new LinkedList<Message>();
	
	private void setupConnection() throws Throwable {
		uri=new URI(url);
		
		// Detect protocol, host, port
		String scheme=uri.getScheme();
		port=uri.getPort();
		hostName=uri.getHost();
		if ("ws".equalsIgnoreCase(scheme) || "http".equals(scheme)) {
			// Default to http
			if (port<0) port=80;
			if (port!=80) hostHeader=hostName + ':' + port;
			else hostHeader=hostName;
			socketFactory=netConfig.getPlainSocketFactory();
		} else if ("wss".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
			// Secure
			if (port<0) port=443;
			if (port!=443) hostHeader=hostName + ':' + port;
			else hostHeader=hostName;
			socketFactory=netConfig.getSecureSocketFactory();
		} else {
			throw new IllegalArgumentException("Unsupported websocket protocol");
		}
		
		// Figure the path
		path=uri.getRawPath();
		if (path.length()==0) path="/";	// Deal with malformed root
		if (uri.getRawQuery()!=null) {
			path+='?' + uri.getRawQuery();
		}
		
		// Connect the socket
		socket=socketFactory.createSocket(hostName, port);
		try {
			// Buffer the streams to a typical network packet size
			in=new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1500));
			out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1500));
		} catch (Throwable t) {
			socket.close();
			throw t;
		}
	}
	
	private void performHandshake() throws Throwable {
		String key1=generateKey();
		String key2=generateKey();
		byte[] quad=new byte[8];
		random.nextBytes(quad);
		
		Map<String,String> headerMap=requestHeaders;
		if (headerMap==null) headerMap=new HashMap<String, String>();
		headerMap.put("Connection", "Upgrade");
		headerMap.put("Upgrade", "WebSocket");
		headerMap.put("Host", hostHeader);
		headerMap.put("Sec-WebSocket-Key1", key1);
		headerMap.put("Sec-WebSocket-Key2", key2);
		if (requestedProtocols!=null && requestedProtocols.length>0) {
			StringBuilder joinedProtocol=new StringBuilder();
			for (String protocol: requestedProtocols) {
				if (joinedProtocol.length()>0) joinedProtocol.append(' ');
				joinedProtocol.append(protocol);
			}
			headerMap.put("Sec-WebSocket-Protocol", joinedProtocol.toString());
		}
		
		// Build the request
		StringBuilder request=new StringBuilder(1500);
		request.append("GET ").append(path).append(" HTTP/1.1\r\n");
		for (Map.Entry<String, String> entry: headerMap.entrySet()) {
			request.append(entry.getKey());
			request.append(": ");
			request.append(entry.getValue());
			request.append("\r\n");
		}
		request.append("\r\n");
		
		System.out.println("Sending request \n'" + request + "'");
		out.write(request.toString().getBytes(UTF8));
		out.flush();	// Give proxys a better chance of dealing with what follows
		out.write(quad);
		out.flush();
		
		// Read the HTTP status line
		String statusLine=readLine(in);
		if (!VERIFY_STATUSLINE_PATTERN.matcher(statusLine).find())
			throw new IOException("Bad status line from server: " + statusLine);
		
		// Read each header line until we get an empty
		responseHeaders=new HashMap<String, String>();
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
		
		protocol=responseHeaders.get("sec-websocket-protocol");
		
		// Now read the handshake from the input and verify
		byte[] serverHandshake=new byte[16];
		in.readFully(serverHandshake);
		validateHandshake(key1, key2, quad, serverHandshake);
		
		// And finally ready to go
		setReadyState(OPEN);
	}

	private void pumpSocketInput() throws Exception {
		for (;;) {
			Message message=framing.readMessage(in);
			if (message==null) break;
			signalMessage(message);
		}
		System.out.println("Socket closed");
	}
	
	private void runReader() {
		setReadyState(CONNECTING);
		
		// Before starting the main loop, we need to resolve the URI
		try {
			setupConnection();
			performHandshake();
			startWriter();
			pumpSocketInput();
		} catch (Throwable t) {
			exceptionalShutdown(t);
			return;
		}
	}
	
	private void runWriter() {
		System.out.println("Writer starting");
		for (;;) {
			synchronized (this) {
				if (shutdownInitiated) break;
			}
			
			Message next;
			synchronized (queue) {
				if (queue.isEmpty()) {
					try {
						queue.wait();
						continue;
					} catch (InterruptedException e) {
						break;
					}
				}
				
				next=queue.removeFirst();
			}
			
			try {
				transmitMessage(next);
			} catch (Throwable t) {
				// Replace the message
				synchronized (queue) {
					queue.addFirst(next);
				}
				exceptionalShutdown(t);
				break;
			}
		}
		System.out.println("Writer exiting");
	}
	
	/**
	 * Physically transmits a message
	 * @param message
	 * @throws IOException 
	 */
	private void transmitMessage(Message message) throws Exception {
		framing.sendMessage(out, message);
	}
	
	/**
	 * Called on exception.  Fires events and shuts everything down.
	 */
	private void exceptionalShutdown(Throwable t) {
		signalError(t);
		if (socket!=null) {
			try {
				socket.close();
				// This will stop anything blocked on read or write
			} catch (IOException e) {
				// Not much else to do
				e.printStackTrace();
			}
			socket=null;
		}
		
		synchronized (this) {
			shutdownInitiated=true;
			if (readerThread!=null && readerThread.isAlive()) readerThread.interrupt();
			if (writerThread!=null && writerThread.isAlive()) writerThread.interrupt();
			readerThread=null;
			writerThread=null;
		}
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
	private String readLine(InputStream in) throws IOException {
		StringBuilder ret=new StringBuilder(256);
		
		boolean verifyLf=false;
		CharsetDecoder decoder=UTF8.newDecoder();
		byte[] source=new byte[256];
		char[] dest=new char[256];
		ByteBuffer sourceBuffer=ByteBuffer.wrap(source);
		CharBuffer destBuffer=CharBuffer.wrap(dest);
		for (;;) {
			in.mark(source.length);
			int r=in.read(source);
			if (r<0) {
				throw new IOException("Short line reading response");
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
		
		return ret.toString();
	}
	
	/**
	 * Generate a sequence of random chars
	 * @param charCount Number of misc ascii chars
	 * @param numCount Number of numeric chars
	 * @param spaceCount Number of spaces
	 * @param armor true to armor the result (add a printable ascii char to front and back)
	 * @return the result
	 */
	private static String generateKey() {
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
	private void validateHandshake(String key1, String key2, byte[] clientQuad,
			byte[] serverHandshake) throws IOException, NoSuchAlgorithmException {
		if (!verifyHandshake) return;
		
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
			System.out.println("Handshakes match");
		}
	}

}

package net.rcode.wsclient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	private static final Pattern INVALID_HEADER_NAME_PATTERN=Pattern.compile("[\\r\\n\\:]");
	private static final Pattern INVALID_HEADER_VALUE_PATTERN=Pattern.compile("[\\r\\n]");

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
		protected int readyState=-1;
		
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
			StringWriter ret=new StringWriter();
			String typeName;
			if (type==EVENT_READYSTATE) typeName="ReadyState";
			else if (type==EVENT_MESSAGE) typeName="Message";
			else if (type==EVENT_ERROR) typeName="Error";
			else typeName=String.valueOf(type);
			
			ret.write("<Event ");
			ret.append(typeName);
			ret.append(", readyState=");
			ret.append(String.valueOf(readyState));
			ret.append(">\n");
			if (type==EVENT_ERROR && error!=null) {
				PrintWriter pout=new PrintWriter(ret);
				error.printStackTrace(pout);
				pout.flush();
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
	private String url;
	private Map<String, String> requestHeaders=new HashMap<String, String>();
	private Map<String, String> responseHeaders;
	private boolean verifyHandshake=true;
	
	// -- public properties (read-write)
	private NetConfig netConfig=new NetConfig();
	private WireProtocol wireProtocol=WireProtocolDraft76.INSTANCE;
	
	public synchronized int getReadyState() {
		return readyState;
	}
	protected synchronized void setReadyState(int readyState) {
		boolean notifyListeners;
		synchronized(this) {
			if (readyState!=this.readyState) {
				this.readyState = readyState;
				notifyListeners=listeners!=null;
				this.notifyAll();
			} else {
				notifyListeners=false;
			}
		}
		
		if (notifyListeners) {
			Event event=new Event();
			event.source=this;
			event.type=EVENT_READYSTATE;
			event.readyState=readyState;
			signalEvent(event);
		}
	}
	
	public synchronized String getProtocol() {
		if (responseHeaders==null) return null;
		return responseHeaders.get("sec-websocket-protocol");
	}
	
	public synchronized String[] getResponseHeaderNames() {
		if (responseHeaders==null) return null;
		return responseHeaders.keySet().toArray(new String[responseHeaders.size()]);
	}
	
	public synchronized String getResponseHeader(String name) {
		if (responseHeaders==null) return null;
		return responseHeaders.get(name.toLowerCase());
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
	
	public WireProtocol getWireProtocol() {
		return wireProtocol;
	}
	
	public void setWireProtocol(WireProtocol wireProtocol) {
		if (started) throw new IllegalStateException();
		this.wireProtocol = wireProtocol;
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
	/**
	 * Queues a message for sending (puts the message at the tail of the queue)
	 */
	public void send(Message message) {
		transmissionQueue.addTail(message);
	}
	
	/**
	 * Queues a message for immediate transmission (puts it at the head of the
	 * queue).
	 * @param message
	 */
	public void sendImmediate(Message message) {
		transmissionQueue.addHead(message);
	}
	
	public void send(CharSequence message) {
		send(new Message(message));
	}
	
	/**
	 * @return the number of messages on the transmission queue
	 */
	public int getOutgoingDepth() {
		return transmissionQueue.getDepth();
	}
	
	/**
	 * @return the approximate number of bytes queued for transmission (excluding framing)
	 */
	public long getOutgoingAmount() {
		return transmissionQueue.getBytes();
	}
	
	public void close() {
		synchronized (this) {
			if (readyState!=OPEN) {
				abort();
				return;
			}
		}
		wireProtocol.initiateClose(this);
	}
	
	/**
	 * Immediately abort the connection.
	 */
	public void abort() {
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
		
		Thread localReaderThread=null, localWriterThread=null;
		synchronized (this) {
			if (readerThread!=null && readerThread.isAlive()) {
				readerThread.interrupt();
				localReaderThread=readerThread;
			}
			if (writerThread!=null && writerThread.isAlive()) {
				writerThread.interrupt();
				localWriterThread=writerThread;
			}
			readerThread=null;
			writerThread=null;
		}

		
		if (localReaderThread!=null) {
			try {
				readerThread.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		if (localWriterThread!=null) {
			try {
				writerThread.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		setReadyState(CLOSED);
	}
	
	public void waitForReadyState(int targetReadyState) throws InterruptedException {
		synchronized (this) {
			while (readyState!=targetReadyState) {
				this.wait();
			}
		}
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
	 */
	public void start() {
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
	
	// -- package private (to protocol implementations)
	private byte[] closeCookie;
	
	protected Map<String, String> getRequestHeaders() {
		return requestHeaders;
	}
	protected String[] getRequestedProtocols() {
		return requestedProtocols;
	}
	protected synchronized void setResponseHeaders(Map<String, String> responseHeaders) {
		this.responseHeaders = responseHeaders;
	}
	protected MessageQueue getTransmissionQueue() {
		return transmissionQueue;
	}
	public synchronized byte[] getCloseCookie() {
		return closeCookie;
	}
	public synchronized void setCloseCookie(byte[] closeCookie) {
		this.closeCookie = closeCookie;
	}
	
	// -- internal implementation
	private boolean started;
	private Thread readerThread, writerThread;
	private String[] requestedProtocols;
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
		//t.printStackTrace();
		Event event=new Event();
		event.source=this;
		event.type=EVENT_ERROR;
		event.readyState=readyState;
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
	private SocketFactory socketFactory;
	private Socket socket;
	private DataInputStream in;
	private DataOutputStream out;
	private MessageQueue transmissionQueue=new MessageQueue();
	
	private void setupConnection() throws Throwable {
		uri=new URI(url);
		
		// Detect protocol, host, port
		String hostHeader;
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
		
		// Add the host header
		requestHeaders.put("Host", hostHeader);
		
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
	

	private void pumpSocketInput() throws Exception {
		for (;;) {
			Message message=wireProtocol.readMessage(this, in);
			if (message==null) break;
			signalMessage(message);
		}
	}
	
	private void runReader() {
		setReadyState(CONNECTING);
		
		// Before starting the main loop, we need to resolve the URI
		try {
			setupConnection();
			wireProtocol.performHandshake(this, uri, in, out);
			startWriter();
			pumpSocketInput();
			abort();
		} catch (Throwable t) {
			exceptionalShutdown(t);
			return;
		}
	}
	
	private void runWriter() {
		//System.out.println("Writer starting");
		for (;;) {
			Message next;
			try {
				next=transmissionQueue.waitNext();
			} catch (InterruptedException e) {
				// Shutdown
				break;
			}
			
			try {
				boolean shouldContinue=wireProtocol.sendMessage(this, out, next);
				transmissionQueue.remove(next);
				if (!shouldContinue) break;
			} catch (Throwable t) {
				// Replace the message
				exceptionalShutdown(t);
				break;
			}
		}
		//System.out.println("Writer exiting");
	}
	
	/**
	 * Called on exception.  Fires events and shuts everything down.
	 */
	private void exceptionalShutdown(Throwable t) {
		signalError(t);
		abort();
	}
}

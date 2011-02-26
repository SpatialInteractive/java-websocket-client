package net.rcode.wsclient;

import android.os.Handler;

/**
 * Android specific WebSocket client that interfaces with the Looper
 * to provide threading control.
 * 
 * @author stella
 *
 */
public class AndroidWebSocket extends WebSocket {
	private Handler handler;
	
	/**
	 * Instantiate the class so that events are directed back at the Looper
	 * attached to this thread.  In typical parlance, this means that you must
	 * instantiate the instance on the main thread.  A task will be posted to
	 * the current thread to start the instance.
	 * 
	 * @param url
	 * @param requestedProtocols
	 */
	public AndroidWebSocket(String url, String... requestedProtocols) {
		this(new Handler(), url, requestedProtocols);
		handler.post(new Runnable() {
			@Override
			public void run() {
				start();
			}
		});
	}

	/**
	 * Instantiate the instance targeting an explicit handler.  This variant is
	 * typically not used.  This constructor does not automatically post a message
	 * to the handler to start the instance.  The caller must arrange to start
	 * it via another means.
	 * 
	 * @param url
	 * @param requestedProtocols
	 */
	public AndroidWebSocket(Handler handler, String url, String... requestedProtocols) {
		super(url, requestedProtocols);
		handler=new Handler();
	}

	@Override
	protected void dispatchEvent(final Event event, final EventListener l) {
		handler.post(new Runnable() {
			@Override
			public void run() {
				l.handleEvent(event);
			}
		});
	}
}

package net.rcode.wsclient;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * Class holding various network configurations
 * @author stella
 *
 */
public class NetConfig {
	private SocketFactory plainSocketFactory;
	private SocketFactory secureSocketFactory;
	
	public void setPlainSocketFactory(SocketFactory plainSocketFactory) {
		this.plainSocketFactory = plainSocketFactory;
	}
	public void setSecureSocketFactory(SocketFactory secureSocketFactory) {
		this.secureSocketFactory = secureSocketFactory;
	}
	
	/**
	 * @return a value previously specified or system default
	 */
	public SocketFactory getPlainSocketFactory() {
		if (plainSocketFactory==null) return SocketFactory.getDefault();
		return plainSocketFactory;
	}
	/**
	 * @return a value previously specified or system default
	 */
	public SocketFactory getSecureSocketFactory() {
		if (secureSocketFactory==null) return SSLSocketFactory.getDefault();
		return secureSocketFactory;
	}
}

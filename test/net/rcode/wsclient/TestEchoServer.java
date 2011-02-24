package net.rcode.wsclient;

import net.rcode.wsclient.WebSocket.Event;

import org.junit.Test;

public class TestEchoServer {

	@Test
	public void testForSmoke() throws Exception {
		WebSocket ws=new WebSocket("ws://echo.websocket.org");
		ws.addRequestHeader("Origin", "http://websocket.org");
		ws.addListener(new WebSocket.EventListener() {
			@Override
			public void handleEvent(Event event) {
				System.out.println("Event: " + event);
			}
		});
		ws.start();
		
		ws.send("Echo me back");
		
		Thread.sleep(300000);
		//ws.close();
	}
}

package net.rcode.wsclient;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * Queue messages for transmission
 * @author stella
 *
 */
public class MessageQueue {
	private LinkedList<Message> queue=new LinkedList<Message>();
	private int count;
	private long bytes;
	
	/**
	 * @return the depth of the queue
	 */
	public int getDepth() {
		synchronized (queue) {
			return count;
		}
	}
	
	/**
	 * @return the approximate number of bytes on the queue
	 */
	public long getBytes() {
		synchronized (queue) {
			return bytes;
		}
	}
	
	/**
	 * Add a message to the start of the queue for transmission
	 * next
	 * @param message
	 */
	public void addHead(Message message) {
		boolean wasEmpty;
		synchronized (queue) {
			wasEmpty=queue.isEmpty();
			bytes+=message.getBytes();
			count++;
			queue.addFirst(message);
			if (wasEmpty) queue.notify();
		}
	}
	
	/**
	 * Add a message to the end of the queue (typical)
	 * @param message
	 */
	public void addTail(Message message) {
		boolean wasEmpty;
		synchronized (queue) {
			wasEmpty=queue.isEmpty();
			bytes+=message.getBytes();
			count++;
			queue.addLast(message);
			if (wasEmpty) queue.notify();
		}
	}
	
	/**
	 * Peek at the next message
	 * @return the next message or null
	 */
	public Message peekNext() {
		synchronized (queue) {
			if (queue.isEmpty()) return null;
			return queue.getFirst();
		}
	}
	
	/**
	 * Blocking wait for next message.  This method will always return
	 * a Message or throw InterruptedException.  The message is not removed
	 * from the queue.
	 * @return Message (never null)
	 * @throws InterruptedException
	 */
	public Message waitNext() throws InterruptedException {
		synchronized (queue) {
			for (;;) {
				if (!queue.isEmpty()) return queue.getFirst();
				else queue.wait();
			}
		}
	}
	
	/**
	 * Returns the next message on the queue, waiting for at most millis.
	 * This only does one blocking call to wait, so calling code should
	 * manage nulls and loops.  Does not remove the message.
	 * @param millis
	 * @return The next message or null
	 * @throws InterruptedException
	 */
	public Message waitNext(long millis) throws InterruptedException {
		synchronized (queue) {
			if (queue.isEmpty()) queue.wait(millis);
			if (queue.isEmpty()) return queue.getFirst();
			else return null;
		}
	}
	
	/**
	 * Remove an existing message from the queue.  Only the first identitical message
	 * is removed.  Typical usage of this method would be in conjunction with
	 * waitNext() to peek at the next message and then remove it once processed.
	 * @param message
	 */
	public void remove(Message message) {
		synchronized (queue) {
			Iterator<Message> iter=queue.iterator();
			while (iter.hasNext()) {
				Message existing=iter.next();
				if (existing==message) {
					count--;
					bytes-=message.getBytes();
					iter.remove();
					return;
				}
			}
		}
	}
}

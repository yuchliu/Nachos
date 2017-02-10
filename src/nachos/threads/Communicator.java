package nachos.threads;

import nachos.machine.*;


/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>, and multiple
 * threads can be waiting to <i>listen</i>. But there should never be a time
 * when both a speaker and a listener are waiting, because the two threads can
 * be paired off at this point.
 */
public class Communicator {
	/**
	 * Allocate a new communicator.
	 */
	public Communicator() {
		this.listenCount = 0;
		this.speakCount = 0;
		this.commLock = new Lock();
		this.speakQueue = new Condition2(this.commLock);
		this.listenQueue = new Condition2(this.commLock);
	}

	/**
	 * Wait for a thread to listen through this communicator, and then transfer
	 * <i>word</i> to the listener.
	 * 
	 * <p>
	 * Does not return until this thread is paired up with a listening thread.
	 * Exactly one listener should receive <i>word</i>.
	 * 
	 * @param word the integer to transfer.
	 */
	public void speak(int word) {
		commLock.acquire();
		++this.speakCount;
		while(inTransaction || listenCount == 0) {
			speakQueue.sleep();
		}
		inTransaction = true;
		msg = word;
		listenQueue.wake();
		--speakCount;
		commLock.release();
	}

	/**
	 * Wait for a thread to speak through this communicator, and then return the
	 * <i>word</i> that thread passed to <tt>speak()</tt>.
	 * 
	 * @return the integer transferred.
	 */
	public int listen() {
		++this.listenCount;
		int ret;
		commLock.acquire();

		while(!inTransaction) {
			if(speakCount>0)
				speakQueue.wake();
			listenQueue.sleep();
		}
		ret = msg;
		inTransaction = false;
		if(listenCount>0 && speakCount>0)
			speakQueue.wake();

		commLock.release();
		//System.out.println("listener " + KThread.currentThread() + " out, " + speakerQueue.getThreadCount() + " speakers " + listenerQueue.getThreadCount() + " listeners");
		--listenCount;
		return ret;
	}

	private Lock commLock = null;
	private Condition2 speakQueue = null;
	private Condition2 listenQueue = null;
	private int speakCount = 0;
	private int listenCount = 0;

	private int msg = 0;
	private  boolean inTransaction = false;
}

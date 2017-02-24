package nachos.threads;

import nachos.machine.*;

import java.util.PriorityQueue;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
	/**
	 * This class is a thread node which contains the returnTime field
	 *
	 0 */
	private static class suspendedThread implements Comparable<suspendedThread> {
		KThread thread;
		long returnTime;

		public suspendedThread(KThread thread, long returnTime) {
			this.thread = thread;
			this.returnTime = returnTime;
		}

		@Override
		public int compareTo(suspendedThread o) {
			if ( this.returnTime == o.returnTime)
				return 0;
			else if ( this.returnTime > o.returnTime )
				return 1;
			else
				return -1;
		}
	}


	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 *
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() {
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		while (!suspendedQueue.isEmpty() && suspendedQueue.peek().returnTime <= Machine.timer().getTime()){
			boolean intStatus = Machine.interrupt().disable();
			suspendedQueue.poll().thread.ready();
			Machine.interrupt().restore(intStatus);
		}
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 *
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 *
	 * @param x the minimum number of clock ticks to wait.
	 *
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		// for now, cheat just to get something working (busy waiting is bad)
		long wakeTime = Machine.timer().getTime() + x;
/*		while (wakeTime > Machine.timer().getTime())
			KThread.yield();*/
		suspendedQueue.add(new suspendedThread(KThread.currentThread(), wakeTime));
		boolean intStatus = Machine.interrupt().disable();
		KThread.sleep(); // DO NOT use yield()! yield() will add the current thread to readyQueue despite the waitUntil time
		Machine.interrupt().restore(intStatus);
	}

	PriorityQueue<suspendedThread> suspendedQueue = new PriorityQueue<suspendedThread>();
}
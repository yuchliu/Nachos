package nachos.threads;

import nachos.machine.*;

/**
 * A KThread is a thread that can be used to execute Nachos kernel code. Nachos
 * allows multiple threads to run concurrently.
 *
 * To create a new thread of execution, first declare a class that implements
 * the <tt>Runnable</tt> interface. That class then implements the <tt>run</tt>
 * method. An instance of the class can then be allocated, passed as an argument
 * when creating <tt>KThread</tt>, and forked. For example, a thread that
 * computes pi could be written as follows:
 *
 * <p>
 * <blockquote>
 *
 * <pre>
 * class PiRun implements Runnable {
 * 	public void run() {
 *         // compute pi
 *         ...
 *     }
 * }
 * </pre>
 *
 * </blockquote>
 * <p>
 * The following code would then create a thread and start it running:
 *
 * <p>
 * <blockquote>
 *
 * <pre>
 * PiRun p = new PiRun();
 * new KThread(p).fork();
 * </pre>
 *
 * </blockquote>
 */
public class KThread {
	/**
	 * Get the current thread.
	 *
	 * @return the current thread.
	 */
	public static KThread currentThread() {
		Lib.assertTrue(currentThread != null);
		return currentThread;
	}

	/**
	 * Allocate a new <tt>KThread</tt>. If this is the first <tt>KThread</tt>,
	 * create an idle thread as well.
	 */
	public KThread() {
		if (currentThread != null) {
			tcb = new TCB();
		}
		else {
			readyQueue = ThreadedKernel.scheduler.newThreadQueue(false);
			readyQueue.acquire(this);

			currentThread = this;
			tcb = TCB.currentTCB();
			name = "main";
			restoreState();

			createIdleThread();
		}
	}

	/**
	 * Allocate a new KThread.
	 *
	 * @param target the object whose <tt>run</tt> method is called.
	 */
	public KThread(Runnable target) {
		this();
		this.target = target;
	}

	/**
	 * Set the target of this thread.
	 *
	 * @param target the object whose <tt>run</tt> method is called.
	 * @return this thread.
	 */
	public KThread setTarget(Runnable target) {
		Lib.assertTrue(status == statusNew);

		this.target = target;
		return this;
	}

	/**
	 * Set the name of this thread. This name is used for debugging purposes
	 * only.
	 *
	 * @param name the name to give to this thread.
	 * @return this thread.
	 */
	public KThread setName(String name) {
		this.name = name;
		return this;
	}

	/**
	 * Get the name of this thread. This name is used for debugging purposes
	 * only.
	 *
	 * @return the name given to this thread.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Get the full name of this thread. This includes its name along with its
	 * numerical ID. This name is used for debugging purposes only.
	 *
	 * @return the full name given to this thread.
	 */
	public String toString() {
		return (name + " (#" + id + ")");
	}

	/**
	 * Deterministically and consistently compare this thread to another thread.
	 */
	public int compareTo(Object o) {
		KThread thread = (KThread) o;

		if (id < thread.id)
			return -1;
		else if (id > thread.id)
			return 1;
		else
			return 0;
	}

	/**
	 * Causes this thread to begin execution. The result is that two threads are
	 * running concurrently: the current thread (which returns from the call to
	 * the <tt>fork</tt> method) and the other thread (which executes its
	 * target's <tt>run</tt> method).
	 */
	public void fork() {
		Lib.assertTrue(status == statusNew);
		Lib.assertTrue(target != null);

		Lib.debug(dbgThread, "Forking thread: " + toString() + " Runnable: "
				+ target);

		boolean intStatus = Machine.interrupt().disable();

		tcb.start(new Runnable() {
			public void run() {
				runThread();
			}
		});

		ready();

		Machine.interrupt().restore(intStatus);
	}

	private void runThread() {
		begin();
		target.run();
		finish();
	}

	private void begin() {
		Lib.debug(dbgThread, "Beginning thread: " + toString());

		Lib.assertTrue(this == currentThread);

		restoreState();

		Machine.interrupt().enable();
	}

	/**
	 * Finish the current thread and schedule it to be destroyed when it is safe
	 * to do so. This method is automatically called when a thread's
	 * <tt>run</tt> method returns, but it may also be called directly.
	 *
	 * The current thread cannot be immediately destroyed because its stack and
	 * other execution state are still in use. Instead, this thread will be
	 * destroyed automatically by the next thread to run, when it is safe to
	 * delete this thread.
	 */
	public static void finish() {
		Lib.debug(dbgThread, "Finishing thread: " + currentThread.toString());

		Machine.interrupt().disable();

		Machine.autoGrader().finishingCurrentThread();

		Lib.assertTrue(toBeDestroyed == null);
		toBeDestroyed = currentThread;

		currentThread.status = statusFinished;
		KThread threadToBeWake;
		while ((threadToBeWake = joinThreadQueue.nextThread()) != null) {
			if (threadToBeWake.status != statusReady)
				threadToBeWake.ready();
		}
		sleep();
	}

	/**
	 * Relinquish the CPU if any other thread is ready to run. If so, put the
	 * current thread on the ready queue, so that it will eventually be
	 * rescheuled.
	 *
	 * <p>
	 * Returns immediately if no other thread is ready to run. Otherwise returns
	 * when the current thread is chosen to run again by
	 * <tt>readyQueue.nextThread()</tt>.
	 *
	 * <p>
	 * Interrupts are disabled, so that the current thread can atomically add
	 * itself to the ready queue and switch to the next thread. On return,
	 * restores interrupts to the previous state, in case <tt>yield()</tt> was
	 * called with interrupts disabled.
	 */
	public static void yield() {
		Lib.debug(dbgThread, "Yielding thread: " + currentThread.toString());

		Lib.assertTrue(currentThread.status == statusRunning);

		boolean intStatus = Machine.interrupt().disable();

		currentThread.ready();

		runNextThread();

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Relinquish the CPU, because the current thread has either finished or it
	 * is blocked. This thread must be the current thread.
	 *
	 * <p>
	 * If the current thread is blocked (on a synchronization primitive, i.e. a
	 * <tt>Semaphore</tt>, <tt>Lock</tt>, or <tt>Condition</tt>), eventually
	 * some thread will wake this thread up, putting it back on the ready queue
	 * so that it can be rescheduled. Otherwise, <tt>finish()</tt> should have
	 * scheduled this thread to be destroyed by the next thread to run.
	 */
	public static void sleep() {
		Lib.debug(dbgThread, "Sleeping thread: " + currentThread.toString());

		Lib.assertTrue(Machine.interrupt().disabled());

		if (currentThread.status != statusFinished)
			currentThread.status = statusBlocked;

		runNextThread();
	}

	/**
	 * Moves this thread to the ready state and adds this to the scheduler's
	 * ready queue.
	 */
	public void ready() {
		Lib.debug(dbgThread, "Ready thread: " + toString());

		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(status != statusReady);

		status = statusReady;
		if (this != idleThread)
			readyQueue.waitForAccess(this);

		Machine.autoGrader().readyThread(this);
	}

	/**
	 * Waits for this thread to finish. If this thread is already finished,
	 * return immediately. This method must only be called once; the second call
	 * is not guaranteed to return. This thread must not be the current thread.
	 */
	public void join() {
		Lib.debug(dbgThread, "Joining to thread: " + toString());

		Lib.assertTrue(this != currentThread);
		boolean intStatus = Machine.interrupt().disable();
		while (this.status != statusFinished) {
			joinThreadQueue.waitForAccess(currentThread);
			sleep();
		}
		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Create the idle thread. Whenever there are no threads ready to be run,
	 * and <tt>runNextThread()</tt> is called, it will run the idle thread. The
	 * idle thread must never block, and it will only be allowed to run when all
	 * other threads are blocked.
	 *
	 * <p>
	 * Note that <tt>ready()</tt> never adds the idle thread to the ready set.
	 */
	private static void createIdleThread() {
		Lib.assertTrue(idleThread == null);

		idleThread = new KThread(new Runnable() {
			public void run() {
				while (true)
					yield();
			}
		});
		idleThread.setName("idle");

		Machine.autoGrader().setIdleThread(idleThread);

		idleThread.fork();
	}

	/**
	 * Determine the next thread to run, then dispatch the CPU to the thread
	 * using <tt>run()</tt>.
	 */
	private static void runNextThread() {
		KThread nextThread = readyQueue.nextThread();
		if (nextThread == null)
			nextThread = idleThread;

		nextThread.run();
	}

	/**
	 * Dispatch the CPU to this thread. Save the state of the current thread,
	 * switch to the new thread by calling <tt>TCB.contextSwitch()</tt>, and
	 * load the state of the new thread. The new thread becomes the current
	 * thread.
	 *
	 * <p>
	 * If the new thread and the old thread are the same, this method must still
	 * call <tt>saveState()</tt>, <tt>contextSwitch()</tt>, and
	 * <tt>restoreState()</tt>.
	 *
	 * <p>
	 * The state of the previously running thread must already have been changed
	 * from running to blocked or ready (depending on whether the thread is
	 * sleeping or yielding).
	 *
	 * @param finishing <tt>true</tt> if the current thread is finished, and
	 * should be destroyed by the new thread.
	 */
	private void run() {
		Lib.assertTrue(Machine.interrupt().disabled());

		Machine.yield();

		currentThread.saveState();

		Lib.debug(dbgThread, "Switching from: " + currentThread.toString()
				+ " to: " + toString());

		currentThread = this;

		tcb.contextSwitch();

		currentThread.restoreState();
	}

	/**
	 * Prepare this thread to be run. Set <tt>status</tt> to
	 * <tt>statusRunning</tt> and check <tt>toBeDestroyed</tt>.
	 */
	protected void restoreState() {
		Lib.debug(dbgThread, "Running thread: " + currentThread.toString());

		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(this == currentThread);
		Lib.assertTrue(tcb == TCB.currentTCB());

		Machine.autoGrader().runningThread(this);

		status = statusRunning;

		if (toBeDestroyed != null) {
			toBeDestroyed.tcb.destroy();
			toBeDestroyed.tcb = null;
			toBeDestroyed = null;
		}
	}

	/**
	 * Prepare this thread to give up the processor. Kernel threads do not need
	 * to do anything here.
	 */
	protected void saveState() {
		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(this == currentThread);
	}

	private static class PingTest implements Runnable {
		PingTest(int which) {
			this.which = which;
		}

		public void run() {
			for (int i = 0; i < 5; i++) {
				System.out.println("*** thread " + which + " looped " + i
						+ " times");
				currentThread.yield();
			}
		}

		private int which;
	}

	private static void joinTest() {
		System.out.println("Join() Test Start!");
		KThread T1 = new KThread(new Runnable() {
			@Override
			public void run() {
				for (int i = 0; i < 3; i++)
					System.out.println("loop" + i + " @T1");
//				currentThread.yield();
			}
		});
		KThread T2 = new KThread(new Runnable() {
			@Override
			public void run() {
				T1.join();
				for (int i = 0; i < 3; i++)
					System.out.println("loop" + i + " @T2");
//				currentThread.yield();
			}
		});
		KThread T3 = new KThread(new Runnable() {
			@Override
			public void run() {
				T2.join();
				for (int i = 0; i < 3; i++)
					System.out.println("loop" + i + " @T3");
//				currentThread.yield();
			}
		});
		T2.fork();
		T1.setName("Thread1").fork();

		T3.fork();
	}

	private static void condVarTest() {
		System.out.println("Conditional Variable Test Start!");
		Lock mutexLock = new Lock();
//		Condition cond = new Condition(mutexLock);
		Condition2 cond = new Condition2(mutexLock);

		KThread T2 = new KThread(new Runnable() {
			@Override
			public void run() {
				for (int i = 0; i < 3; i++)
					System.out.println("loop" + i + " @T2");
			}
		});

		KThread T1 = new KThread(new Runnable() {
			@Override
			public void run() {
				T2.join();
				for (int i = 0; i < 50; i++) {
					if (i == 48) {
						mutexLock.acquire();
						cond.wake();
						mutexLock.release();
					}
					System.out.println("loop" + i + " @T1");
				}
			}
		});

		KThread T3 = new KThread(new Runnable() {
			@Override
			public void run() {
				for (int i = 0; i < 3; i++) {
					if (i == 2) {
						mutexLock.acquire();
						cond.sleep();
						mutexLock.release();
					}
					System.out.println("loop" + i + " @T3");
				}
			}
		});



		T3.fork();
		T2.fork();
		T1.fork();

		// should not join T2 here since we know T2 is joined in T1 already
		T1.join();
		T3.join();
	}

	private static void alarmTest() {
		System.out.println("Alarm Test Start!");
		Alarm myAlarm = new Alarm();
		KThread T1 = new KThread(new Runnable() {
			@Override
			public void run() {
				System.out.println("T1 starts @" + Machine.timer().getTime());
				// timerInterrupt() is called at tick 490, 1020 in our test
				// T1 start at 170, x = 321 so it is 492>490, for testing
				myAlarm.waitUntil(322);
				for (int i = 0; i < 3; i++)
					System.out.println("loop" + i + " @T1 @" + Machine.timer().getTime());
//				currentThread.yield();
			}
		});

		KThread T2 = new KThread(new Runnable() {
			@Override
			public void run() {
				for (int i = 0; i < 3; i++)
					System.out.println("loop" + i + " @T2 @" + Machine.timer().getTime());
//				currentThread.yield();
			}
		});

		KThread T3 = new KThread(new Runnable() {
			@Override
			public void run() {
				System.out.println("T3 starts @" + Machine.timer().getTime());
				// T3 starts at 190 ticks in our test
				// Thus, T3 has return time @491 while T1's return time is 492
				// We implemented by priorityQueue(ordered by return time)
				// Glad to see T3 continue first before T1 because of priority
				myAlarm.waitUntil(301);
				for (int i = 0; i < 3; i++)
					System.out.println("loop" + i + " @T3 @" + Machine.timer().getTime());
//				currentThread.yield();
			}
		});
		T1.fork();
		T2.fork();
		T3.fork();
		T1.join();
		T2.join();
		T3.join();
	}

	private static void commTest() {
		System.out.println("commTest Start!");
		Alarm myAlarm = new Alarm();
		Communicator comm = new Communicator();
		KThread T1 = new KThread(new Runnable() {
			@Override
			public void run() {
				int msg = 1;
				myAlarm.waitUntil(2500);
				System.out.println("T1 starts speaking@" + Machine.timer().getTime());
				comm.speak(msg);
				System.out.println("T1 return from speak() @" + Machine.timer().getTime());
			}
		});

		KThread T2 = new KThread(new Runnable() {
			@Override
			public void run() {
				int msg = 2;
				myAlarm.waitUntil(0);
				System.out.println("T2 starts speaking@" + Machine.timer().getTime());
				comm.speak(msg);
				System.out.println("T2 return from speak() @" + Machine.timer().getTime());
			}
		});

		KThread T3 = new KThread(new Runnable() {
			@Override
			public void run() {
				int msg = 3;
				myAlarm.waitUntil(500);
				System.out.println("T3 starts listening@" + Machine.timer().getTime());
				int received = comm.listen();
				System.out.println("T3 return from listen() with msg#" + received +  " @" + Machine.timer().getTime());
			}
		});

		KThread T4 = new KThread(new Runnable() {
			@Override
			public void run() {
				int msg = 4;
				myAlarm.waitUntil(1800);
				System.out.println("T4 starts listening@" + Machine.timer().getTime());
				int received = comm.listen();
				System.out.println("T4 return from listen() with msg#" + received + " @" + Machine.timer().getTime());
			}
		});
		T1.setName("Speaker1").fork();
		T2.setName("Speaker2").fork();
		T3.setName("Listener1").fork();
		T4.setName("Listener2").fork();
		T1.join();
		T2.join();
		T3.join();
		T4.join();
	}

	private static void PQTest() {
		System.out.println("PQ Test Start!");
		// no need to run thread for testing the scheduler,
		// we just need to check the change of priority
		ThreadQueue pq1 = ThreadedKernel.scheduler.newThreadQueue(true);
		ThreadQueue pq2 = ThreadedKernel.scheduler.newThreadQueue(true);
		ThreadQueue pq3 = ThreadedKernel.scheduler.newThreadQueue(true);
		KThread thread1 = new KThread();
		KThread thread2 = new KThread();
		KThread thread3 = new KThread();
		KThread thread4 = new KThread();
		KThread thread5 = new KThread();

		thread1.setName("T1");
		thread2.setName("T2");
		thread3.setName("T3");
		thread4.setName("T4");
		thread5.setName("T5");

		// user should disable interrupt before calling waitForAccess() and acquire()
		boolean status = Machine.interrupt().disable();


		pq1.waitForAccess(thread1);
		pq2.waitForAccess(thread2);
		pq3.waitForAccess(thread3);

		pq1.acquire(thread2);
		pq2.acquire(thread3);
		pq3.acquire(thread4);
//        System.out.println("Priority of thread1 is " + ThreadedKernel.scheduler.getPriority(thread1) + " , Effective: " + ThreadedKernel.scheduler.getEffectivePriority(thread1));
		ThreadedKernel.scheduler.setPriority(thread1, 6);

//        System.out.println("Priority of thread1 is " + ThreadedKernel.scheduler.getPriority(thread1) + " , Effective: " + ThreadedKernel.scheduler.getEffectivePriority(thread1));


		// when the priority of thread2 be promoted, thread3 are promoted too, same as thread4
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread2)==6);
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread4)==6);

		ThreadedKernel.scheduler.setPriority(thread5, 7);
		pq1.waitForAccess(thread5);

		// because no thread is running, so the pq1 is still acquired by thread2
		// Thus, thread1's priority will not be affected.
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread1)==6);
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread2)==7);
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread3)==7);
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread4)==7);

		ThreadedKernel.scheduler.setPriority(thread2, 3);
		// even manual set the priority of thread2 to 3, by recalculating effective priority,
		// thread2 ,3 4 still have effective priority 7 because thread 5
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread2)==7);
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread3)==7);
		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread4)==7);

		// once the pq1 is unlocked from thread2. thread2, 3, 4's effective priority will depend on the highest one
		// i.e. thread2's priority
		pq1.nextThread();

		Lib.assertTrue(ThreadedKernel.scheduler.getEffectivePriority(thread4)==3);

		Machine.interrupt().restore(status);
	}
	/**
	 *
	 * Tests whether this module is working.
	 */
	public static void selfTest() {
		Lib.debug(dbgThread, "Enter KThread.selfTest");

//		new KThread(new PingTest(1)).setName("forked thread").fork();
//		new PingTest(0).run();
/*		System.out.println(Machine.timer().getTime());
		for (long i = 0; i < 100000000; i++){}
		System.out.println(Machine.timer().getTime());*/
//      	joinTest();
//		condVarTest();
//		alarmTest();
		commTest();
//		PQTest();
	}

	private static final char dbgThread = 't';

	/**
	 * Additional state used by schedulers.
	 *
	 * @see nachos.threads.PriorityScheduler.ThreadState
	 */
	public Object schedulingState = null;

	private static final int statusNew = 0;

	private static final int statusReady = 1;

	private static final int statusRunning = 2;

	private static final int statusBlocked = 3;

	private static final int statusFinished = 4;

	/**
	 * The status of this thread. A thread can either be new (not yet forked),
	 * ready (on the ready queue but not running), running, or blocked (not on
	 * the ready queue and not running).
	 */
	private int status = statusNew;

	private String name = "(unnamed thread)";

	private Runnable target;

	private TCB tcb;

	/**
	 * Unique identifer for this thread. Used to deterministically compare
	 * threads.
	 */
	private int id = numCreated++;

	/** Number of times the KThread constructor was called. */
	private static int numCreated = 0;

	private static ThreadQueue readyQueue = null;

	private static KThread currentThread = null;

	private static KThread toBeDestroyed = null;

	private static KThread idleThread = null;

	private static ThreadQueue joinThreadQueue = ThreadedKernel.scheduler.newThreadQueue(true);
}
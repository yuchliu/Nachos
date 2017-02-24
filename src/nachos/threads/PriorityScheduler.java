package nachos.threads;

import nachos.machine.*;

import java.util.*;

/**
 * Created by cjk98 on 2/10/2017.
 */

/**
 * A scheduler that chooses threads based on their priorities.
 *
 * <p>
 * A priority scheduler associates a priority with each thread. The next thread
 * to be dequeued is always a thread with priority no less than any other
 * waiting thread's priority. Like a round-robin scheduler, the thread that is
 * dequeued is, among all the threads of the same (highest) priority, the thread
 * that has been waiting longest.
 *
 * <p>
 * Essentially, a priority scheduler gives access in a round-robin fashion to
 * all the highest-priority threads, and ignores all other threads. This has the
 * potential to starve a thread if there's always a thread waiting with higher
 * priority.
 *
 * <p>
 * A priority scheduler must partially solve the priority inversion problem; in
 * particular, priority must be donated through locks, and through joins.
 */
public class PriorityScheduler extends Scheduler {
    /**
     * Allocate a new priority scheduler.
     */
    public PriorityScheduler() {
    }

    /**
     * Allocate a new priority thread queue.
     *
     * @param transferPriority <tt>true</tt> if this queue should transfer
     * priority from waiting threads to the owning thread.
     * @return a new priority thread queue.
     */
    public ThreadQueue newThreadQueue(boolean transferPriority) {
        return new PriorityQueue(transferPriority);
    }

    public int getPriority(KThread thread) {
        Lib.assertTrue(Machine.interrupt().disabled());

        return getThreadState(thread).getPriority();
    }

    public int getEffectivePriority(KThread thread) {
        Lib.assertTrue(Machine.interrupt().disabled());

        return getThreadState(thread).getEffectivePriority();
    }

    public void setPriority(KThread thread, int priority) {
        Lib.assertTrue(Machine.interrupt().disabled());

        Lib.assertTrue(priority >= priorityMinimum
                && priority <= priorityMaximum);

        getThreadState(thread).setPriority(priority);
    }

    public boolean increasePriority() {
        boolean intStatus = Machine.interrupt().disable();
        boolean ret = true;

        KThread thread = KThread.currentThread();

        int priority = getPriority(thread);
        if (priority == priorityMaximum)
            ret = false;
        else
            setPriority(thread, priority + 1);

        Machine.interrupt().restore(intStatus);
        return ret;
    }

    public boolean decreasePriority() {
        boolean intStatus = Machine.interrupt().disable();
        boolean ret = true;

        KThread thread = KThread.currentThread();

        int priority = getPriority(thread);
        if (priority == priorityMinimum)
            ret = false;
        else
            setPriority(thread, priority - 1);

        Machine.interrupt().restore(intStatus);
        return ret;
    }

    /**
     * The default priority for a new thread. Do not change this value.
     */
    public static final int priorityDefault = 1;

    /**
     * The minimum priority that a thread can have. Do not change this value.
     */
    public static final int priorityMinimum = 0;

    /**
     * The maximum priority that a thread can have. Do not change this value.
     */
    public static final int priorityMaximum = 7;

    /**
     * Return the scheduling state of the specified thread.
     *
     * @param thread the thread whose scheduling state to return.
     * @return the scheduling state of the specified thread.
     */
    protected ThreadState getThreadState(KThread thread) {
        if (thread.schedulingState == null)
            thread.schedulingState = new ThreadState(thread);

        return (ThreadState) thread.schedulingState;
    }

    // This is the comparator use to sort waitThreads(nachosPQ) in the PriorityQueue
    private static class BY_THREADSTATE implements Comparator<ThreadState> {
        private nachos.threads.PriorityScheduler.PriorityQueue threadWaitQueue;

        public BY_THREADSTATE(nachos.threads.PriorityScheduler.PriorityQueue pq) {
            this.threadWaitQueue = pq;
        }

        @Override
        public int compare(ThreadState o1, ThreadState o2) {
            int ePriority1 = o1.getEffectivePriority(), ePriority2 = o2.getEffectivePriority();
            if (ePriority1 > ePriority2) {
                return -1;
            }
            else if (ePriority1 < ePriority2) {
                return 1;
            }
            else { // equal effective priority, order by the time these thread wait
                long waitTime1 = o1.waitingMap.get(threadWaitQueue), waitTime2 = o2.waitingMap.get(threadWaitQueue);

                if (waitTime1 < waitTime2) {
                    return -1;
                }
                else if(waitTime1 > waitTime2) {
                    return 1;
                }
                else {
                    return 0;
                }
            }
        }
    }

    /**
     * A <tt>ThreadQueue</tt> that sorts threads by priority.
     */
    protected class PriorityQueue extends ThreadQueue {
        /**
         * <tt>true</tt> if this queue should transfer priority from waiting
         * threads to the owning thread.
         */
        public boolean transferPriority;

        // explicitly declare java.util.PriorityQueue in case of confusion to compiler
        // the threads who are waiting for this resource (PQ)
        private java.util.PriorityQueue<ThreadState> waitThreads = new java.util.PriorityQueue<ThreadState>(8,new BY_THREADSTATE(this));

        // the thread who acquire the resource represented by this PQ
        private KThread threadHolding = null;

        PriorityQueue(boolean transferPriority) {
            this.transferPriority = transferPriority;
        }

        public void waitForAccess(KThread thread) {
            Lib.assertTrue(Machine.interrupt().disabled());
            getThreadState(thread).waitForAccess(this);
        }

        public void acquire(KThread thread) {
            Lib.assertTrue(Machine.interrupt().disabled());
            getThreadState(thread).acquire(this);
        }

        public KThread nextThread() {
            Lib.assertTrue(Machine.interrupt().disabled());
            if (waitThreads.isEmpty()) {
                // no thread is waiting
                return null;
            }
            else {
                // let next thread (top of the waitThreads) to acquire resource
                acquire(waitThreads.poll().thread);
                return threadHolding;
            }
        }

        /**
         * Return the next thread that <tt>nextThread()</tt> would return,
         * without modifying the state of this queue.
         *
         * @return the next thread that <tt>nextThread()</tt> would return.
         */
        protected ThreadState pickNextThread() {
            // peek() will not remove the thread in waitThreads
            return waitThreads.peek();
        }

        public void print() {
        }
    }

    /**
     * The scheduling state of a thread. This should include the thread's
     * priority, its effective priority, any objects it owns, and the queue it's
     * waiting for, if any.
     *
     * @see nachos.threads.KThread#schedulingState
     */
    protected class ThreadState {
        /** The thread with which this object is associated. */
        protected KThread thread;
        /** The priority of the associated thread. */
        protected int priority;
        // Cached effective priority
        protected int effectivePriority;
        // a set to store which resources (nachosPQ) this thread has acquired
        private HashSet<nachos.threads.PriorityScheduler.PriorityQueue> acquiredSet = new HashSet<nachos.threads.PriorityScheduler.PriorityQueue>();

        // a Map to store resources (nachosPQ) that this thread is waiting for
        // Also, the time a thread start waiting a resources is stored in the map for breaking tie
        // The waitThreads queue in each resources are sorted by the effective priority, when two threads has the
        // same priority, the thread that wait longer should be placed ahead of the other thread in the queue
        protected HashMap<PriorityQueue,Long> waitingMap = new HashMap<nachos.threads.PriorityScheduler.PriorityQueue,Long>();


        /**
         * Allocate a new <tt>ThreadState</tt> object and associate it with the
         * specified thread.
         *
         * @param thread the thread this state belongs to.
         */
        public ThreadState(KThread thread) {
            this.thread = thread;
            this.priority= priorityDefault;
            this.effectivePriority = priorityDefault;
        }

        /**
         * Return the priority of the associated thread.
         *
         * @return the priority of the associated thread.
         */
        public int getPriority() {
            return priority;
        }

        /**
         * Return the effective priority of the associated thread.
         *
         * @return the effective priority of the associated thread.
         */
        public int getEffectivePriority() {
            return effectivePriority;
        }

        /**
         * Set the priority of the associated thread to the specified value.
         *
         * @param priority the new priority.
         */
        public void setPriority(int priority) {
            // in case we do unnecessary updateEffectivePriority()
            if (this.priority == priority)
                return;
            this.priority = priority;
            // we should update effectivePriority(not even ours, but also other threads') since priority changed
            updateEffectivePriority();
        }

        /*
        *
        */
        private void updateEffectivePriority() {
            // iterate all resource (nachosPQ) in the thread's waitingMap
            // remove this thread from the resource's waitThreads queue first
            // (after changing effective priority, we will put it back again
            for (PriorityQueue pq : waitingMap.keySet())
                pq.waitThreads.remove(this);

            int tempPriority = priority;
            /*
            * the priority inversion is only happening and need to be dealt with if
            * this thread acquire some resource(and there is another higher priority thread need the resource)
            * so we iterate all of the resources in our acquriedSet and find the effective priority
            * we set the effective priority to the highest possible one (each thread could only
            * have ONE priority/effective priority at a time)
            */
            int topPriority;
            for (PriorityQueue pq : acquiredSet) {
                if (pq.transferPriority) {
                    ThreadState topThread = pq.waitThreads.peek();
                    if (topThread != null) {
                        topPriority = topThread.getEffectivePriority();
                        if (topPriority > tempPriority)
                            tempPriority = topPriority;
                    }
                }
            }

            // if need a priority donation, if equal to cached effective priority,
            // we do not need to the priority donation again (that is costly)
            boolean needDonation;
            if (tempPriority != effectivePriority)
                needDonation = true;
            else
                needDonation = false;

            // cached new effectivePriority
            effectivePriority = tempPriority;

            // iterate all resource (nachosPQ) in the thread's waitingMap
            // add this thread back into resource's waitThreads queue with NEW effective priority
            for (PriorityQueue pq : waitingMap.keySet())
                pq.waitThreads.add(this);

            // if a donation has to be done, iterate all resources in our waitingMap
            // find the holding thread of each resource and notice it to updateEffectivePriority
            if (needDonation)
                for (PriorityQueue pq : waitingMap.keySet()) {
                    if (pq.transferPriority && pq.threadHolding != null)
                        getThreadState(pq.threadHolding).updateEffectivePriority();
                }
        }

        /**
         * Called when <tt>waitForAccess(thread)</tt> (where <tt>thread</tt> is
         * the associated thread) is invoked on the specified priority queue.
         * The associated thread is therefore waiting for access to the resource
         * guarded by <tt>waitQueue</tt>. This method is only called if the
         * associated thread cannot immediately obtain access.
         *
         * @param waitQueue the queue that the associated thread is now waiting
         * on.
         *
         * @see nachos.threads.ThreadQueue#waitForAccess
         */
        public void waitForAccess(PriorityQueue waitQueue) {
            // make sure the resources was not in our waitingMap already
            if (!waitingMap.containsKey(waitQueue)) {
                // dealing with some rare case (error handling)
                // the calling thread may wanna wait for a resource that is already acquired
                // since this is what she/he likes, we kindly release the resource for it and make him wait
                release(waitQueue);

                // record the system time and put the resource in our waitingMap with priority
                waitingMap.put(waitQueue, Machine.timer().getTime());
                // Also, we should add this thread into the resource's waiting queue(waitiThreads)
                waitQueue.waitThreads.add(this);

                // if the resource is held by other thread, we should updateEffectivePriority
                // in case of the priority inversion
                if (waitQueue.threadHolding != null) {
                    getThreadState(waitQueue.threadHolding).updateEffectivePriority();
                }
            }
        }

        /**
         * Called when the associated thread has acquired access to whatever is
         * guarded by <tt>waitQueue</tt>. This can occur either as a result of
         * <tt>acquire(thread)</tt> being invoked on <tt>waitQueue</tt> (where
         * <tt>thread</tt> is the associated thread), or as a result of
         * <tt>nextThread()</tt> being invoked on <tt>waitQueue</tt>.
         *
         * @see nachos.threads.ThreadQueue#acquire
         * @see nachos.threads.ThreadQueue#nextThread
         */
        public void acquire(PriorityQueue waitQueue) {
            // by definition, acquire() should only be called when there is no thread holding the resource(nachosPQ)
            // here just make sure, if there is, release it
            if (waitQueue.threadHolding != null) {
                getThreadState(waitQueue.threadHolding).release(waitQueue);
            }

            //ATTENTION: the input argument of the API is waitQueue, try not to be mislead
            // if this thread WAS waiting for the resources before,
            // acquiring the resource means we should remove this thread out of the resources waitQueue
            // this remove will do nothing
            waitQueue.waitThreads.remove(this);

            // now we acquire the resource(nachosPQ)
            waitQueue.threadHolding = this.thread;
            // add the resource to our acquiredSet
            acquiredSet.add(waitQueue);
            // remove the resource from our waitingMap
            waitingMap.remove(waitQueue);
            // be sure to updateEffectivePriority since we change the threadHolding of the resource "waitQueue"
            // it is possible that the this new holder has a low priority
            // than a thread waiting to access this resource
            updateEffectivePriority();
        }

        /*
        * in any case the calling thread do not need this resource,
        * or the calling thread
        */
        private void release(PriorityQueue priorityQueue) {
            // remove priorityQueue from my acquired set
            if (acquiredSet.remove(priorityQueue)) {
                priorityQueue.threadHolding = null;
                updateEffectivePriority();
            }
        }
    }
}
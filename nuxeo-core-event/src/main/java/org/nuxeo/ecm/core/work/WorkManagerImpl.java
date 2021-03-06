/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 *     Benoit Delbosc
 */
package org.nuxeo.ecm.core.work;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import javax.naming.NamingException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.work.api.Work;
import org.nuxeo.ecm.core.work.api.Work.State;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.core.work.api.WorkQueueDescriptor;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Timer;

/**
 * The implementation of a {@link WorkManager}.
 *
 * @since 5.6
 */
public class WorkManagerImpl extends DefaultComponent implements WorkManager {

    private static final Log log = LogFactory.getLog(WorkManagerImpl.class);

    public static final String DEFAULT_QUEUE_ID = "default";

    public static final String DEFAULT_CATEGORY = "default";

    protected static final String QUEUES_EP = "queues";

    protected WorkQueueDescriptorRegistry workQueueDescriptors;

    @Override
    public void activate(ComponentContext context) throws Exception {
        super.activate(context);
        workQueueDescriptors = new WorkQueueDescriptorRegistry();
        init();
    }

    @Override
    public void deactivate(ComponentContext context) throws Exception {
        shutdown(1, TimeUnit.SECONDS);
        workQueueDescriptors = null;
        super.deactivate(context);
    }

    @Override
    public void registerContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor)
            throws Exception {
        if (QUEUES_EP.equals(extensionPoint)) {
            WorkQueueDescriptor workQueueDescriptor = (WorkQueueDescriptor) contribution;
            log.info("Registered work queue " + workQueueDescriptor.id);
            workQueueDescriptors.addContribution(workQueueDescriptor);
        }
    }

    @Override
    public void unregisterContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor)
            throws Exception {
        if (QUEUES_EP.equals(extensionPoint)) {
            WorkQueueDescriptor workQueueDescriptor = (WorkQueueDescriptor) contribution;
            log.info("Unregistered work queue " + workQueueDescriptor.id);
            workQueueDescriptors.removeContribution(workQueueDescriptor);
        }
    }

    @Override
    public List<String> getWorkQueueIds() {
        return workQueueDescriptors.getQueueIds();
    }

    @Override
    public WorkQueueDescriptor getWorkQueueDescriptor(String queueId) {
        return workQueueDescriptors.get(queueId);
    }

    @Override
    public String getCategoryQueueId(String category) {
        if (category == null) {
            category = DEFAULT_CATEGORY;
        }
        String queueId = workQueueDescriptors.getQueueId(category);
        if (queueId == null) {
            queueId = DEFAULT_QUEUE_ID;
        }
        return queueId;
    }

    // ----- WorkManager -----

    /**
     * Creates non-daemon threads at normal priority.
     */
    public static class NamedThreadFactory implements ThreadFactory {

        private final AtomicInteger threadNumber = new AtomicInteger();

        private final ThreadGroup group;

        private final String prefix;

        public NamedThreadFactory(String prefix) {
            SecurityManager sm = System.getSecurityManager();
            group = sm == null ? Thread.currentThread().getThreadGroup()
                    : sm.getThreadGroup();
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            String name = prefix + threadNumber.incrementAndGet();
            Thread thread = new Thread(group, r, name);
            // do not set daemon
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }

    /**
     * A handler for rejected tasks that suspends them.
     */
    public static class SuspendPolicy implements RejectedExecutionHandler {

        public static final SuspendPolicy INSTANCE = new SuspendPolicy();

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            ((WorkThreadPoolExecutor) executor).suspendFromQueue(r);
        }
    }

    /**
     * Synchronization holding a {@link Work} instance until commit time, at
     * which point it will be scheduled.
     *
     * @since 5.7
     */
    public static class WorkSchedulingSynchronization implements
            Synchronization {

        protected final Work work;

        protected final WorkThreadPoolExecutor executor;

        public WorkSchedulingSynchronization(Work work,
                WorkThreadPoolExecutor executor) {
            this.work = work;
            this.executor = executor;
        }

        @Override
        public void beforeCompletion() {
        }

        @Override
        public void afterCompletion(int status) {
            if (work.getState() != State.SCHEDULED) {
                // work already suspended or canceled
                return;
            }
            if (status == Status.STATUS_COMMITTED) {
                executor.afterCommit(work);
            } else if (status == Status.STATUS_ROLLEDBACK) {
                executor.cancelScheduledAfterCommit(work);
            } else {
                log.error("Unexpected status after completion: " + status);
            }
        }
    }

    /**
     * List of {@link Work} instances.
     *
     * @since 5.7
     */
    public static class WorkList extends LinkedList<Work> {
        private static final long serialVersionUID = 1L;

        /**
         * Remove by identity, not equals.
         * <p>
         * {@inheritDoc}
         */
        @Override
        public boolean remove(Object o) {
            for (Iterator<Work> it = iterator(); it.hasNext();) {
                Work w = it.next();
                if (w == o) { // not equals()
                    it.remove();
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * A {@link ThreadPoolExecutor} that keeps available the list of scheduled,
     * running and completed tasks and provides other methods.
     * <p>
     * The methods checking the sizes are sure not to lose tasks in transit
     * between the various queues.
     *
     * @since 5.6
     */
    public static class WorkThreadPoolExecutor extends ThreadPoolExecutor {

        protected Object monitor = new Object();

        protected WorkList scheduledAfterCommit;

        protected WorkList scheduled;

        protected WorkList running;

        protected WorkList completed;

        protected WorkList suspended;

        // @since 5.7
        protected final Counter scheduledCount;

        protected final Counter scheduledMax;

        protected final Counter runningCount;

        protected final Counter completedCount;

        protected final Timer workTimer;

        public WorkThreadPoolExecutor(String queueId, int corePoolSize,
                int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                BlockingQueue<Runnable> queue, ThreadFactory threadFactory) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit, queue,
                    threadFactory);
            scheduledAfterCommit = new WorkList();
            scheduled = new WorkList();
            running = new WorkList();
            completed = new WorkList();
            suspended = new WorkList();
            scheduledCount = Metrics.defaultRegistry().newCounter(
                    WorkThreadPoolExecutor.class, "scheduled", queueId);
            scheduledMax = Metrics.defaultRegistry().newCounter(
                    WorkThreadPoolExecutor.class, "scheduled-max", queueId);
            runningCount = Metrics.defaultRegistry().newCounter(
                    WorkThreadPoolExecutor.class, "running", queueId);
            completedCount = Metrics.defaultRegistry().newCounter(
                    WorkThreadPoolExecutor.class, "completed", queueId);
            workTimer = Metrics.defaultRegistry().newTimer(
                    WorkThreadPoolExecutor.class, "work", queueId,
                    TimeUnit.MICROSECONDS, TimeUnit.SECONDS);
        }

        /**
         * Removes any work instances equal to this one from the scheduled queue
         * and cancels them.
         *
         * @param work the work to cancel
         * @return {@code true} if there was work to cancel
         */
        public boolean cancelScheduled(Work work) {
            boolean removed = false;
            while (getQueue().remove(work)) {
                removed = true;
            }
            if (removed) {
                synchronized (monitor) {
                    for (Iterator<Work> it = scheduledAfterCommit.iterator(); it.hasNext();) {
                        Work w = it.next();
                        if (work.equals(w)) {
                            it.remove();
                            w.setCanceled();
                        }
                    }
                    for (Iterator<Work> it = scheduled.iterator(); it.hasNext();) {
                        Work w = it.next();
                        if (work.equals(w)) {
                            it.remove();
                            w.setCanceled();
                        }
                    }
                }
            }
            return removed;
        }

        /**
         * Called from {@link WorkSchedulingSynchronization} after commit to
         * actually remove the work from the queue and cancel it.
         */
        public void cancelScheduledAfterCommit(Work work) {
            boolean removed;
            synchronized (monitor) {
                removed = scheduledAfterCommit.remove(work);
            }
            if (removed) { // may have been removed before tx commit
                work.setCanceled();
            }
        }

        /**
         * Finds a work instance in the scheduled or running queue.
         *
         * @param work the work to find
         * @param state the state defining the queue to look in,
         *            {@link State#SCHEDULED SCHEDULED}, {@link State#RUNNING
         *            RUNNING}, {@link State#COMPLETED COMPLETED}, or
         *            {@code null} for non-completed
         * @param useEquals if {@code true} then use {@link Work#equals} to find
         *            the work instance, otherwise use object identity
         * @param pos a 1-element array to return the position in the internal
         *            queue
         * @return the found work instance
         */
        public Work find(Work work, State state, boolean useEquals, int[] pos) {
            List<List<Work>> queues = new LinkedList<List<Work>>();
            if (state == null) {
                queues.add(running);
                queues.add(scheduled);
                queues.add(scheduledAfterCommit);
            } else if (state == State.RUNNING) {
                queues.add(running);
            } else if (state == State.SCHEDULED) {
                queues.add(scheduled);
                queues.add(scheduledAfterCommit);
            } else if (state == State.COMPLETED) {
                queues.add(completed);
            } else {
                throw new IllegalArgumentException(String.valueOf(state));
            }
            synchronized (monitor) {
                for (List<Work> queue : queues) {
                    int i = -1;
                    for (Work w : queue) {
                        i++;
                        boolean found = useEquals ? w.equals(work) : w == work;
                        if (found) {
                            if (pos != null) {
                                pos[0] = i;
                            }
                            return w;
                        }
                    }
                }
            }
            if (pos != null) {
                pos[0] = -1;
            }
            return null;
        }

        /**
         * This method does not wait for transaction to be committed to schedule
         * the task for execution.
         * <p>
         * {@inheritDoc}
         *
         * @param r the task to execute
         */
        @Override
        public void execute(Runnable r) {
            execute((Work) r, false);
        }

        /**
         * Executes the given task sometime in the future.
         *
         * @param work the work to execute
         * @param afterCommit if {@code true} and the work is scheduled, it will
         *            only be run after the current transaction (if any) has
         *            committed
         * @see #execute(Runnable)
         */
        public void execute(Work work, boolean afterCommit) {
            scheduledCount.inc();
            if (scheduledCount.getCount() > scheduledMax.getCount()) {
                scheduledMax.inc();
            }
            if (afterCommit) {
                TransactionManager transactionManager;
                try {
                    transactionManager = TransactionHelper.lookupTransactionManager();
                } catch (NamingException e) {
                    transactionManager = null;
                }
                if (transactionManager != null) {
                    try {
                        Transaction transaction = transactionManager.getTransaction();
                        if (transaction != null
                                && transaction.getStatus() == Status.STATUS_ACTIVE) {
                            transaction.registerSynchronization(new WorkSchedulingSynchronization(
                                    work, this));
                            synchronized (monitor) {
                                scheduledAfterCommit.add(work);
                            }
                            return;
                        }
                    } catch (SystemException e) {
                        // ignore
                    } catch (RollbackException e) {
                        // ignore, cannot happen
                    }
                }
            }
            synchronized (monitor) {
                scheduled.add(work);
            }
            super.execute(work);
        }

        /**
         * Called from {@link WorkSchedulingSynchronization} after commit to
         * actually schedule the work.
         */
        public void afterCommit(Work work) {
            boolean removed;
            synchronized (monitor) {
                removed = scheduledAfterCommit.remove(work);
                if (removed) { // may have been removed before tx commit
                    scheduled.add(work);
                }
            }
            if (removed) {
                super.execute(work);
            }
        }

        @Override
        protected void beforeExecute(Thread t, Runnable r) {
            synchronized (monitor) {
                Work work = (Work) r;
                scheduled.remove(work);
                running.add(work);
                work.beforeRun(); // change state
                scheduledCount.dec();
                runningCount.inc();
            }
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            synchronized (monitor) {
                runningCount.dec();
                Work work = (Work) r;
                work.afterRun(t == null); // change state
                workTimer.update(
                        work.getCompletionTime() - work.getStartTime(),
                        TimeUnit.MILLISECONDS);
                running.remove(work);
                if (work.getState() == State.SUSPENDED) {
                    suspended.add(work);
                } else {
                    completed.add(work);
                    completedCount.inc();
                }
            }
        }

        // called during shutdown
        // with tasks from the queue if new tasks are submitted
        // or with tasks drained from the queue
        protected void suspendFromQueue(Runnable r) {
            Work work = (Work) r;
            work.suspend();
            if (work.getState() != State.SUSPENDED) {
                log.error("Work failed to suspend from queue on shutdown: "
                        + work);
                return;
            }
            synchronized (monitor) {
                scheduled.remove(work);
                suspended.add(work);
            }
        }

        /**
         * Initiates a shutdown of this executor and asks for work instances to
         * suspend themselves.
         */
        public void shutdownAndSuspend() {
            // mark executor as shutting down
            setRejectedExecutionHandler(SuspendPolicy.INSTANCE);
            shutdown();
            // notify all work instances that they should suspend asap
            suspend();
        }

        /**
         * Blocks until all work instances have completed after a shutdown and
         * suspend request. Suspended work is saved.
         *
         * @param timeout the time to wait
         * @param unit the timeout unit
         * @return true if all work stopped or was saved, false if some
         *         remaining after timeout
         */
        public boolean awaitTerminationOrSave(long timeout, TimeUnit unit)
                throws InterruptedException {
            boolean terminated = awaitTermination(timeout, unit);
            if (!terminated) {
                // drain queue to suspend remaining scheduled work
                List<Runnable> toSuspend = new ArrayList<Runnable>();
                getQueue().drainTo(toSuspend);
                for (Runnable r : toSuspend) {
                    suspendFromQueue(r);
                }
            }
            // this sync would only block work scheduled after the shutdown
            List<Work> toSave;
            synchronized (monitor) {
                toSave = new ArrayList<Work>(suspended);
                suspended.clear();
            }
            for (Work work : toSave) {
                if (work.getState() != State.SUSPENDED) {
                    log.error("Work in suspended queue but not suspended: "
                            + work);
                    continue;
                }
                @SuppressWarnings("unused")
                Map<String, Serializable> data = work.getData();
                // TODO save data
            }
            // some work still remaining after timeout
            return terminated;
        }

        /**
         * Requests all running and scheduled work instances to suspend.
         */
        public void suspend() {
            synchronized (monitor) {
                for (Work work : running) {
                    work.suspend();
                }
                for (Work work : scheduled) {
                    work.suspend();
                }
                for (Work work : scheduledAfterCommit) {
                    work.suspend();
                }
            }
        }

        /**
         * Gets the scheduled tasks. Returns a copy.
         */
        public List<Work> getScheduled() {
            synchronized (monitor) {
                List<Work> list = new ArrayList<Work>(scheduled);
                list.addAll(scheduledAfterCommit);
                return list;
            }
        }

        /**
         * Gets the running tasks. Returns a copy.
         */
        public List<Work> getRunning() {
            synchronized (monitor) {
                return new ArrayList<Work>(running);
            }
        }

        /**
         * Gets the completed tasks. Returns a copy.
         */
        public List<Work> getCompleted() {
            synchronized (monitor) {
                return new ArrayList<Work>(completed);
            }
        }

        /**
         * Gets the non-completed tasks. Returns a copy.
         */
        public List<Work> getNonCompleted() {
            synchronized (monitor) {
                List<Work> list = new ArrayList<Work>(running.size()
                        + scheduled.size());
                list.addAll(running);
                list.addAll(scheduled);
                list.addAll(scheduledAfterCommit);
                return list;
            }
        }

        /**
         * Gets the number of non-completed tasks.
         */
        public int getNonCompletedWorkSize() {
            synchronized (monitor) {
                return scheduled.size() + scheduledAfterCommit.size()
                        + running.size();
            }
        }

        /**
         * Clears the completed tasks.
         */
        public void clearCompleted() {
            synchronized (monitor) {
                completed.clear();
            }
        }

        /**
         * Clears the completed tasks older than the given date.
         */
        public void clearCompleted(long completionTime) {
            if (completionTime <= 0) {
                clearCompleted();
                return;
            }
            synchronized (monitor) {
                for (Iterator<Work> it = completed.iterator(); it.hasNext();) {
                    Work work = it.next();
                    if (work.getCompletionTime() < completionTime) {
                        it.remove();
                    }
                }
            }
        }
    }

    protected static final int DEFAULT_MAX_POOL_SIZE = 4;

    private static final String THREAD_PREFIX = "Nuxeo-Work-";

    protected Map<String, WorkThreadPoolExecutor> executors;

    @Override
    public void init() {
        executors = new HashMap<String, WorkThreadPoolExecutor>();
    }

    protected synchronized boolean hasExecutor(String queueId) {
        return executors.containsKey(queueId);
    }

    protected synchronized WorkThreadPoolExecutor removeExecutor(String queueId) {
        return executors.remove(queueId);
    }

    protected synchronized WorkThreadPoolExecutor getExecutor(String queueId) {
        WorkQueueDescriptor workQueueDescriptor = workQueueDescriptors.get(queueId);
        if (workQueueDescriptor == null) {
            throw new IllegalArgumentException("No such work queue: " + queueId);
        }

        WorkThreadPoolExecutor executor = executors.get(queueId);
        if (executor == null) {
            ThreadFactory threadFactory = new NamedThreadFactory(THREAD_PREFIX
                    + queueId + "-");
            int maxPoolSize = workQueueDescriptor.maxThreads;
            if (maxPoolSize <= 0) {
                maxPoolSize = DEFAULT_MAX_POOL_SIZE;
                workQueueDescriptor.maxThreads = maxPoolSize;
            }
            executor = new WorkThreadPoolExecutor(queueId, maxPoolSize,
                    maxPoolSize, 0, TimeUnit.SECONDS,
                    newBlockingQueue(workQueueDescriptor), threadFactory);
            executors.put(queueId, executor);
        }
        return executor;
    }

    /**
     * A LinkedBlockingQueue that blocks on "offer" and prevent starvation
     * deadlock on reentrant call.
     *
     */
    public class NuxeoLinkedBlockingQueue<T> extends LinkedBlockingQueue<T> {

        private static final long serialVersionUID = -5798585349765219199L;

        private final ReentrantLock limitedPutLock = new ReentrantLock();

        private final int limitedCapacity;

        public NuxeoLinkedBlockingQueue(int capacity) {
            // Allocate more space to prevent starvation dead lock
            // because a worker can add a new job to the queue.
            super(2 * capacity);
            limitedCapacity = capacity;
        }

        /**
         * Block until there are enough remaining capacity to put the entry.
         */
        public void limitedPut(T e) throws InterruptedException {
            limitedPutLock.lockInterruptibly();
            try {
                while (remainingCapacity() < limitedCapacity) {
                    Thread.sleep(100);
                }
                put(e);
            } finally {
                limitedPutLock.unlock();
            }

        }

        @Override
        public boolean offer(T e) {
            // Patch to turn non blocking offer into a blocking put
            try {
                if (Thread.currentThread().getName().startsWith(THREAD_PREFIX)) {
                    // use the full queue capacity for reentrant call
                    put(e);
                } else {
                    // put only if there are enough remaining capacity
                    limitedPut(e);
                }
                return true;
            } catch (InterruptedException e1) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

    }

    protected BlockingQueue<Runnable> newBlockingQueue(
            WorkQueueDescriptor descriptor) {
        int capacity = descriptor.capacity;
        if (descriptor.usePriority) {
            if (capacity > 0) {
                log.warn(String.format(
                        "Priority queue can not have limited capacity, either disable capacity or priority on EP %s:%s ",
                        descriptor.id, descriptor.name));
            }
            return new PriorityBlockingQueue<Runnable>();
        }
        if (capacity > 0) {
            return new NuxeoLinkedBlockingQueue<Runnable>(descriptor.capacity);
        }
        // default unbounded queue
        return new LinkedBlockingQueue<Runnable>();
    }

    @Override
    public boolean shutdownQueue(String queueId, long timeout, TimeUnit unit)
            throws InterruptedException {
        WorkThreadPoolExecutor executor = getExecutor(queueId);
        boolean terminated = shutdownExecutors(Collections.singleton(executor),
                timeout, unit);
        removeExecutor(queueId); // start afresh
        return terminated;
    }

    @Override
    public synchronized boolean shutdown(long timeout, TimeUnit unit)
            throws InterruptedException {
        if (executors == null) {
            return true;
        }
        List<WorkThreadPoolExecutor> executorList = new ArrayList<WorkThreadPoolExecutor>(
                executors.values());
        executors = null;

        return shutdownExecutors(executorList, timeout, unit);
    }

    protected boolean shutdownExecutors(
            Collection<WorkThreadPoolExecutor> list, long timeout, TimeUnit unit)
            throws InterruptedException {
        // mark executors as shutting down
        for (WorkThreadPoolExecutor executor : list) {
            executor.shutdownAndSuspend();
        }

        long t0 = System.currentTimeMillis();
        long delay = unit.toMillis(timeout);

        // wait for termination or suspension
        boolean terminated = true;
        for (WorkThreadPoolExecutor executor : list) {
            if (!executor.awaitTerminationOrSave(remainingMillis(t0, delay),
                    TimeUnit.MILLISECONDS)) {
                terminated = false;
                // hard shutdown for remaining tasks
                executor.shutdownNow();
            }
        }

        return terminated;
    }

    protected long remainingMillis(long t0, long delay) {
        long d = System.currentTimeMillis() - t0;
        if (d > delay) {
            return 0;
        }
        return delay - d;
    }

    @Override
    public void schedule(Work work) {
        schedule(work, Scheduling.ENQUEUE, false);
    }

    @Override
    public void schedule(Work work, boolean afterCommit) {
        schedule(work, Scheduling.ENQUEUE, afterCommit);
    }

    @Override
    public void schedule(Work work, Scheduling scheduling) {
        schedule(work, scheduling, false);
    }

    @Override
    public void schedule(Work work, Scheduling scheduling, boolean afterCommit) {
        if (work.getState() != State.SCHEDULED) {
            throw new IllegalStateException(String.valueOf(work.getState()));
        }
        String queueId = getCategoryQueueId(work.getCategory());
        if (log.isDebugEnabled()) {
            log.debug("Scheduling work: " + work + " using queue: " + queueId);
        }
        WorkThreadPoolExecutor executor = getExecutor(queueId);
        switch (scheduling) {
        case ENQUEUE:
            break;
        case CANCEL_SCHEDULED:
            executor.cancelScheduled(work);
            break;
        case IF_NOT_SCHEDULED:
        case IF_NOT_RUNNING:
        case IF_NOT_RUNNING_OR_SCHEDULED:
            if (executor.find(work, scheduling.state, true, null) != null) {
                work.setCanceled();
                return;
            }
            break;
        }
        executor.execute(work, afterCommit);
    }

    @Override
    public Work find(Work work, State state, boolean useEquals, int[] pos) {
        String queueId = getCategoryQueueId(work.getCategory());
        return getExecutor(queueId).find(work, state, useEquals, pos);
    }

    @Override
    public List<Work> listWork(String queueId, State state) {
        if (state == null) {
            return getExecutor(queueId).getNonCompleted();
        }
        switch (state) {
        case SCHEDULED:
            return getExecutor(queueId).getScheduled();
        case RUNNING:
            return getExecutor(queueId).getRunning();
        case COMPLETED:
            return getExecutor(queueId).getCompleted();
        default:
            throw new IllegalArgumentException(String.valueOf(state));
        }
    }

    @Override
    public int getNonCompletedWorkSize(String queueId) {
        return getExecutor(queueId).getNonCompletedWorkSize();
    }

    @Override
    public boolean awaitCompletion(String queueId, long timeout, TimeUnit unit)
            throws InterruptedException {
        return awaitCompletion(Collections.singleton(queueId), timeout, unit);
    }

    @Override
    public boolean awaitCompletion(long timeout, TimeUnit unit)
            throws InterruptedException {
        return awaitCompletion(getWorkQueueIds(), timeout, unit);
    }

    protected boolean awaitCompletion(Collection<String> queueIds,
            long timeout, TimeUnit unit) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        long delay = unit.toMillis(timeout);
        for (;;) {
            boolean completed = true;
            for (String queueId : queueIds) {
                if (getNonCompletedWorkSize(queueId) != 0) {
                    completed = false;
                    break;
                }
            }
            if (completed) {
                return true;
            }
            if (System.currentTimeMillis() - t0 > delay) {
                return false;
            }
            Thread.sleep(50);
        }
    }

    @Override
    public void clearCompletedWork(String queueId) {
        getExecutor(queueId).clearCompleted();
    }

    @Override
    public synchronized void clearCompletedWork(long completionTime) {
        for (WorkThreadPoolExecutor executor : executors.values()) {
            executor.clearCompleted(completionTime);
        }
    }

    @Override
    public synchronized void cleanup() {
        log.debug("Clearing old completed work");
        for (Entry<String, WorkThreadPoolExecutor> es : executors.entrySet()) {
            String queueId = es.getKey();
            WorkThreadPoolExecutor executor = es.getValue();
            WorkQueueDescriptor descr = workQueueDescriptors.get(queueId);
            long delay = descr.clearCompletedAfterSeconds * 1000L;
            if (delay > 0) {
                long completionTime = System.currentTimeMillis() - delay;
                executor.clearCompleted(completionTime);
            }
        }
    }

}

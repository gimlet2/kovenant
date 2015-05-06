/*
 * Copyright (c) 2015 Mark Platvoet<mplatvoet@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * THE SOFTWARE.
 */

package nl.mplatvoet.komponents.kovenant

import java.util.ArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


public fun buildDispatcher(body: DispatcherBuilder.() -> Unit): Dispatcher {
    val builder = ConcreteDispatcherBuilder()
    builder.body()
    return builder.build()
}

trait DispatcherBuilder {
    var name: String
    var numberOfThreads: Int
    var exceptionHandler: (Exception) -> Unit
    var errorHandler: (Throwable) -> Unit
    fun configurePollStrategy(body: PollStrategyBuilder.() -> Unit)
}

private class ConcreteDispatcherBuilder : DispatcherBuilder {
    private var localName = "dispatcher"
    private var localNumberOfThreads = threadAdvice
    private var localExceptionHandler: (Exception) -> Unit = { e -> e.printStackTrace(System.err) }
    private var localErrorHandler: (Throwable) -> Unit = { t -> t.printStackTrace(System.err) }

    private val workQueue = WorkQueue<() -> Unit>()
    private val pollStrategyBuilder = ConcretePollStrategyBuilder(workQueue)

    override var name: String
        get() = localName
        set(value) {
            localName = value
        }


    override var numberOfThreads: Int
        get() = localNumberOfThreads
        set(value) {
            if (value < 1) {
                throw IllegalArgumentException("numberOfThreads must be at least 1, but was $value")
            }
            localNumberOfThreads = value
        }

    override var exceptionHandler: (Exception) -> Unit
        get() = localExceptionHandler
        set(value) {
            localExceptionHandler = value
        }

    override var errorHandler: (Throwable) -> Unit
        get() = localErrorHandler
        set(value) {
            localErrorHandler = value
        }


    override fun configurePollStrategy(body: PollStrategyBuilder.() -> Unit) {
        pollStrategyBuilder.clear()
        pollStrategyBuilder.body()
    }

    fun build(): Dispatcher {
        return NonBlockingDispatcher(name = localName,
                numberOfThreads = localNumberOfThreads,
                exceptionHandler = localExceptionHandler,
                errorHandler = localErrorHandler,
                workQueue = workQueue,
                pollStrategy = pollStrategyBuilder.build())
    }


}

trait PollStrategyBuilder {
    fun yielding(numberOfPolls: Int = 1000)
    fun busy(numberOfPolls: Int = 1000)
    fun blocking()
    fun sleeping(numberOfPolls: Int = 10, sleepTimeInMs: Long = 10)
    fun blockingSleep(numberOfPolls: Int = 10, sleepTimeInMs: Long = 10)
}

class ConcretePollStrategyBuilder(private val workQueue: WorkQueue<() -> Unit>) : PollStrategyBuilder {
    private val strategies = ArrayList<PollStrategy<() -> Unit>>()

    fun clear() = strategies.clear()

    override fun yielding(numberOfPolls: Int) {
        strategies add YieldingPollStrategy(pollable = workQueue, attempts = numberOfPolls)
    }

    override fun sleeping(numberOfPolls: Int, sleepTimeInMs: Long) {
        strategies add SleepingPollStrategy(pollable = workQueue, attempts = numberOfPolls, sleepTimeMs = sleepTimeInMs)
    }

    override fun busy(numberOfPolls: Int) {
        strategies add BusyPollStrategy(pollable = workQueue, attempts = numberOfPolls)
    }

    override fun blocking() {
        strategies add BlockingPollStrategy(pollable = workQueue)
    }

    override fun blockingSleep(numberOfPolls: Int, sleepTimeInMs: Long) {
        strategies add BlockingSleepPollStrategy(pollable = workQueue, attempts = numberOfPolls, sleepTimeMs = sleepTimeInMs)
    }

    private fun buildDefaultStrategy(): PollStrategy<() -> Unit> {
        return ChainPollStrategy(listOf(YieldingPollStrategy(workQueue), SleepingPollStrategy(workQueue)))
    }

    fun build(): PollStrategy<() -> Unit> = if (strategies.isEmpty()) {
        buildDefaultStrategy()
    } else {
        ChainPollStrategy(strategies)
    }
}


private class NonBlockingDispatcher(val name: String,
                                    val numberOfThreads: Int,
                                    private val exceptionHandler: (Exception) -> Unit,
                                    private val errorHandler: (Throwable) -> Unit,
                                    private val workQueue: WorkQueue<() -> Unit>,
                                    private val pollStrategy: PollStrategy<() -> Unit>) : Dispatcher {

    init {
        if (numberOfThreads < 1) {
            throw IllegalArgumentException("numberOfThreads must be at least 1 but was $numberOfThreads")
        }
    }

    private val running = AtomicBoolean(true)
    private val threadId = AtomicInteger(0)
    private val contextCount = AtomicInteger(0)

    private val threadContexts = ConcurrentLinkedQueue<ThreadContext>()


    override fun offer(task: () -> Unit): Boolean {
        if (running.get()) {
            workQueue offer task
            val threadSize = contextCount.get()
            if (threadSize < numberOfThreads) {
                val threadNumber = contextCount.incrementAndGet()
                if (threadNumber <= numberOfThreads && (threadNumber == 1 || threadNumber < workQueue.size())) {
                    val newThreadContext = newThreadContext()
                    threadContexts offer newThreadContext
                    if (!running.get()) {
                        //it can be the case that during initialization of the context the dispatcher has been shutdown
                        //and this newly created created is missed. So request shutdown again.
                        newThreadContext.interrupt()
                    }

                } else {
                    contextCount.decrementAndGet()
                }
            }
            return true
        }
        return false
    }

    override fun stop(force: Boolean, timeOutMs: Long, block: Boolean): List<() -> Unit> {
        if (running.compareAndSet(true, false)) {

            //Notify all thread to simply die as soon as possible
            threadContexts.forEach { it.kamikaze() }

            if (block) {
                val localTimeOutMs = if (force) 1 else timeOutMs
                val now = System.currentTimeMillis()
                val napTimeMs = Math.min(timeOutMs, 10L)

                fun allThreadsShutdown() = contextCount.get() <= 0
                fun keepWaiting() = localTimeOutMs < 1 || (System.currentTimeMillis() - now) >= localTimeOutMs

                var interrupted = false
                while (!allThreadsShutdown() && keepWaiting()) {
                    try {
                        Thread.sleep(napTimeMs)
                    } catch (e: InterruptedException) {
                        //ignoring for now since it would break the shutdown contract
                        //remember and interrupt later
                        interrupted = true
                    }
                }
                if (interrupted) {
                    //calling thread was interrupted during shutdown, set the interrupted flag again
                    Thread.currentThread().interrupt()
                }

                threadContexts.forEach { it.interrupt() }

                return depleteQueue()
            }
        }
        return ArrayList() //depleteQueue() also returns an ArrayList, returning this for consistency
    }

    override val terminated: Boolean get() = stopped && contextCount.get() < 0

    override val stopped: Boolean get() = !running.get()

    private fun depleteQueue(): List<() -> Unit> {
        val remains = ArrayList<() -> Unit>()
        do {
            val function = workQueue.poll()
            if (function == null) return remains
            remains.add(function)
        } while (true)
        throw IllegalStateException("unreachable")
    }

    override fun tryCancel(task: () -> Unit): Boolean = workQueue.remove(task)

    private fun newThreadContext(): ThreadContext {
        val threadName = "${name}-${threadId.incrementAndGet()}"

        return ThreadContext(threadName = threadName)
    }


    internal fun deRegisterRequest(context: ThreadContext, force: Boolean = false): Boolean {

        val succeeded = threadContexts.remove(context)
        if (!force && succeeded && threadContexts.isEmpty() && workQueue.isNotEmpty() && running.get()) {
            //that, hopefully rare, state where all threadContexts thought they had nothing to do
            //but the queue isn't empty. Reinstate anyone that notes this.
            threadContexts.add(context)
            return false
        }
        if (succeeded) {
            contextCount.decrementAndGet()
        }
        return true
    }


    private inner class ThreadContext(val threadName: String) {

        private val pending = 0
        private val running = 1
        private val polling = 2
        private val mutating = 3

        private val state = AtomicInteger(pending)
        private val thread = Thread() { run() }
        private volatile var alive = true
        private volatile var keepAlive: Boolean = true


        init {
            thread.setName(threadName)
            thread.setDaemon(false)
            thread.start()
        }


        private fun changeState(expect: Int, update: Int) {
            while (state.compareAndSet(expect, update));
        }

        private fun tryChangeState(expect: Int, update: Int): Boolean = state.compareAndSet(expect, update)

        private fun run() {
            while (alive) {
                var pollResult: (() -> Unit)? = null
                changeState(pending, polling)
                try {
                    pollResult = if (keepAlive) pollStrategy.get() else workQueue.poll(block = false)
                    if (!keepAlive || pollResult == null) {
                        //not interested in keeping alive and no work left. Die.
                        if (deRegisterRequest(this)) {
                            //de register succeeded, shutdown this context.
                            interrupt()
                        }
                    }

                } catch(e: InterruptedException) {
                    if (!alive) {
                        //only set the interrupted flag again if thread is interrupted via this context.
                        //otherwise either user code has interrupted the thread and we can ignore that
                        //or this thread has become kamikaze and we can ignore that too
                        thread.interrupt()
                    }
                } finally {
                    changeState(polling, pending)
                    if (!keepAlive && alive) {
                        //if at this point keepAlive is false but the context itself is alive
                        //the thread might be in an interrupted state, we need to clear that because
                        //we are probably in graceful shutdown mode and we don't want to interfere with any
                        //tasks that need to be executed
                        Thread.interrupted()

                        if (!alive) {
                            //oh you concurrency, you tricky bastard. We might just cleared that interrupted flag
                            //but it can be the case that after checking all the flags this context was interrupted
                            //for a full shutdown and we just cleared that. So interrupt again.
                            thread.interrupt()
                        }
                    }
                }

                val fn = pollResult
                if (fn != null) {
                    changeState(pending, running)


                    try {
                        fn()
                    } catch(e: InterruptedException) {
                        if (!alive) {
                            //only set the interrupted flag again if thread is interrupted via this context.
                            //otherwise user code has interrupted the thread, we can ignore that.
                            thread.interrupt()
                        }
                    } catch(e: Exception) {
                        exceptionHandler(e)
                    } catch(t: Throwable) {
                        //Okay this can be anything. Most likely out of memory errors, so everything can go haywire
                        //from here. Let's try to gracefully dismiss this thread by un registering from the pool and die.
                        deRegisterRequest(this, force = true)

                        errorHandler(t)
                    } finally {
                        changeState(running, pending)
                    }

                }
            }
        }


        /*
        Become 'kamikaze' or just really suicidal. Try to bypass the pollStrategy for quick and clean death.
         */
        fun kamikaze() {
            keepAlive = false
            if (tryChangeState(polling, mutating)) {
                //this thread is in the pollin state and we are going to interrupt it.
                //because our polling strategies might be blocked
                thread.interrupt()
                changeState(mutating, polling)
            }

        }

        fun interrupt() {
            alive = false
            thread.interrupt()
            deRegisterRequest(this, force = true)
        }
    }


}


private class WorkQueue<V : Any>() : Offerable<V>, Pollable<V> {
    private val queue = ConcurrentLinkedQueue<V>()
    private val waitingThreads = AtomicInteger(0)

    //yes I could also use a ReentrantLock with a Condition but
    //introduces quite a lot of overhead and the semantics
    //are just the same
    private val mutex = Object()

    public fun size(): Int = queue.size()

    public fun isEmpty(): Boolean = queue.isEmpty()
    public fun isNotEmpty(): Boolean = !isEmpty()

    public fun remove(elem: Any?): Boolean = queue.remove(elem)

    override fun offer(elem: V): Boolean {
        val added = queue offer elem

        if (added && waitingThreads.get() > 0) {
            synchronized(mutex) {
                //maybe there aren't any threads waiting or
                //there isn't anything in the queue anymore
                //just notify, we've got this far
                mutex.notifyAll()
            }
        }

        return added
    }

    override fun poll(block: Boolean, timeoutMs: Long): V? {
        if (!block) return queue.poll()

        val elem = queue.poll()
        if (elem != null) return elem

        waitingThreads.incrementAndGet()
        try {
            return if (timeoutMs > -1L) {
                blockingPoll(timeoutMs)
            } else {
                blockingPoll()
            }
        } finally {
            waitingThreads.decrementAndGet()
        }

    }

    private fun blockingPoll(): V? {
        synchronized(mutex) {
            while (true) {
                val retry = queue.poll()
                if (retry != null) return retry
                mutex.wait()
            }
        }
        throw IllegalStateException("unreachable")
    }

    private fun blockingPoll(timeoutMs: Long): V? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(mutex) {
            while (true) {
                val retry = queue.poll()
                if (retry != null || System.currentTimeMillis() >= deadline) return retry
                mutex.wait(timeoutMs)
            }
        }
        throw IllegalStateException("unreachable")
    }
}

private trait Offerable<V : Any> {
    fun offer(elem: V): Boolean
}

private trait Pollable<V : Any> {
    throws(javaClass<InterruptedException>())
    fun poll(block: Boolean = false, timeoutMs: Long = -1L): V?
}


private trait PollStrategy<V : Any> {
    throws(javaClass<InterruptedException>())
    fun get(): V?
}

private class ChainPollStrategy<V : Any>(private val strategies: List<PollStrategy<V>>) : PollStrategy<V> {
    override fun get(): V? {
        strategies.forEach { strategy ->
            val result = strategy.get()
            if (result != null) return result
        }
        return null
    }
}

private class YieldingPollStrategy<V : Any>(private val pollable: Pollable<V>,
                                            private val attempts: Int = 1000) : PollStrategy<V> {
    override fun get(): V? {
        for (i in 0..attempts) {
            val value = pollable.poll(block = false)
            if (value != null) return value
            Thread.yield()
        }
        return null
    }
}

private class BusyPollStrategy<V : Any>(private val pollable: Pollable<V>,
                                        private val attempts: Int = 1000) : PollStrategy<V> {
    override fun get(): V? {
        for (i in 0..attempts) {
            val value = pollable.poll(block = false)
            if (value != null) return value
        }
        return null
    }
}

private class SleepingPollStrategy<V : Any>(private val pollable: Pollable<V>,
                                            private val attempts: Int = 100,
                                            private val sleepTimeMs: Long = 10) : PollStrategy<V> {
    override fun get(): V? {
        for (i in 0..attempts) {
            val value = pollable.poll(block = false)
            if (value != null) return value
            Thread.sleep(sleepTimeMs)
        }
        return null
    }
}

private class BlockingSleepPollStrategy<V : Any>(private val pollable: Pollable<V>,
                                                 private val attempts: Int = 100,
                                                 private val sleepTimeMs: Long = 10) : PollStrategy<V> {
    override fun get(): V? {
        for (i in 0..attempts) {
            val value = pollable.poll(block = true, timeoutMs = sleepTimeMs)
            if (value != null) return value
        }
        return null
    }
}

private class BlockingPollStrategy<V : Any>(private val pollable: Pollable<V>) : PollStrategy<V> {
    override fun get(): V? = pollable.poll(block = true)
}

//on multicores, leave one thread out so that
//the dispatcher thread can run on it's own core
private val threadAdvice: Int
    get() = Math.max(availableProcessors - 1, 1)

private val availableProcessors: Int
    get() = Runtime.getRuntime().availableProcessors()


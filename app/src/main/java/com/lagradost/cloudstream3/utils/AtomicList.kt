package com.lagradost.cloudstream3.utils

import java.util.concurrent.locks.ReentrantLock

/**
 * A thread-safe list backed by [SynchronizedObject].
 *
 * For iteration, wrap block in [withLock] to hold the lock for the duration:
 *   list.withLock { list.forEach { ... } }
 */
open class AtomicList<T>(
    private val delegate: List<T> = emptyList(),
) : List<T> {

    // Round 3: kotlin.concurrent.synchronized does not resolve under
    // Kotlin 1.9.24 (probe). A private ReentrantLock behind the same
    // public withLock() API; reentrant, like the monitor was.
    private val lock = ReentrantLock()

    fun <R> withLock(block: () -> R): R {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun filter(predicate: (T) -> Boolean): AtomicList<T> = withLock { AtomicList(delegate.filter(predicate)) }
    fun distinctBy(selector: (T) -> Any?): AtomicList<T> = withLock { AtomicList(delegate.distinctBy(selector)) }

    override val size: Int get() = withLock { delegate.size }
    override fun isEmpty(): Boolean = withLock { delegate.isEmpty() }
    override fun contains(element: T): Boolean = withLock { delegate.contains(element) }
    override fun containsAll(elements: Collection<T>): Boolean = withLock { delegate.containsAll(elements) }
    override fun get(index: Int): T = withLock { delegate[index] }
    override fun indexOf(element: T): Int = withLock { delegate.indexOf(element) }
    override fun lastIndexOf(element: T): Int = withLock { delegate.lastIndexOf(element) }

    // Iterators intentionally NOT synchronized, callers must use withLock { } for safe iteration.
    override fun iterator(): Iterator<T> = delegate.iterator()
    override fun listIterator(): ListIterator<T> = delegate.listIterator()
    override fun listIterator(index: Int): ListIterator<T> = delegate.listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): List<T> = delegate.subList(fromIndex, toIndex)

    operator fun plus(element: T): AtomicList<T> = withLock { AtomicList(delegate + element) }
    operator fun plus(elements: Collection<T>): AtomicList<T> = withLock { AtomicList(delegate + elements) }
}

class AtomicMutableList<T>(
    private val mutableDelegate: MutableList<T> = mutableListOf(),
) : AtomicList<T>(mutableDelegate), MutableList<T> {

    override fun add(element: T): Boolean = withLock { mutableDelegate.add(element) }
    override fun add(index: Int, element: T) = withLock { mutableDelegate.add(index, element) }
    override fun addAll(elements: Collection<T>): Boolean = withLock { mutableDelegate.addAll(elements) }
    override fun addAll(index: Int, elements: Collection<T>): Boolean = withLock { mutableDelegate.addAll(index, elements) }
    override fun remove(element: T): Boolean = withLock { mutableDelegate.remove(element) }
    override fun removeAt(index: Int): T = withLock { mutableDelegate.removeAt(index) }
    override fun removeAll(elements: Collection<T>): Boolean = withLock { mutableDelegate.removeAll(elements) }
    override fun retainAll(elements: Collection<T>): Boolean = withLock { mutableDelegate.retainAll(elements) }
    override fun set(index: Int, element: T): T = withLock { mutableDelegate.set(index, element) }
    override fun clear() = withLock { mutableDelegate.clear() }

    // Iterators intentionally NOT synchronized, callers must use withLock { } for safe iteration.
    override fun iterator(): MutableIterator<T> = mutableDelegate.iterator()
    override fun listIterator(): MutableListIterator<T> = mutableDelegate.listIterator()
    override fun listIterator(index: Int): MutableListIterator<T> = mutableDelegate.listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = mutableDelegate.subList(fromIndex, toIndex)
}

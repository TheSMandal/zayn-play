package com.lagradost.cloudstream3.utils

/**
 * (v0.2.6) Verbatim from upstream (CloudStream app module). Restored
 * because plugins reference it (StremioX / StreamPlay / Ultima reach
 * MainActivity.Companion events of this type in their load closure);
 * without it those plugins fail verification with load_class_missing.
 * No Android or CloudStream-UI dependency.
 */
class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()

    val size: Int get() = observers.size

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) {
            observers.add(observer)
        }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) {
            observers.remove(observer)
        }
    }

    operator fun invoke(value: T) {
        synchronized(observers) {
            for (observer in observers)
                observer(value)
        }
    }
}

class EmptyEvent {
    private val observers = mutableSetOf<Runnable>()

    val size: Int get() = observers.size

    operator fun plusAssign(observer: Runnable) {
        synchronized(observers) {
            observers.add(observer)
        }
    }

    operator fun minusAssign(observer: Runnable) {
        synchronized(observers) {
            observers.remove(observer)
        }
    }

    operator fun invoke() {
        synchronized(observers) {
            for (observer in observers)
                observer.run()
        }
    }
}

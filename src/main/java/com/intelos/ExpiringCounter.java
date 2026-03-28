package com.intelos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread-safe sliding-window event counter.
 *
 * <p>Events are recorded via {@link #record()} and counted via {@link #getCount()}.
 * Only events that occurred within the last {@code windowMillis} milliseconds
 * (exclusive at the lower boundary) are included in the count.
 *
 * <p>Internally the counter maintains a {@link DoublyLinkedList} of time-bucketed
 * nodes (one node per distinct millisecond in which at least one event occurred).
 * The head of the list holds the most recent bucket; the tail holds the oldest.
 * An expiry callback is scheduled for every new bucket; callbacks are executed
 * lazily on the next call to {@link #record()} or {@link #getCount()}.
 */
public class ExpiringCounter {

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /** One bucket representing all events that occurred at the same millisecond. */
    static class EventData {
        final long time;
        final AtomicInteger numOfEvents;

        EventData(long time) {
            this.time = time;
            this.numOfEvents = new AtomicInteger(1);
        }
    }

    /**
     * A lazily-executed callback that expires a single bucket and all older
     * buckets that are still present in the list.
     */
    private static class Callback {
        final long expiryTime;
        final DoublyLinkedList.Node<EventData> node;

        Callback(long expiryTime, DoublyLinkedList.Node<EventData> node) {
            this.expiryTime = expiryTime;
            this.node = node;
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final long windowMillis;
    private final Clock clock;

    /** Total number of live events (kept in sync with the DLL contents). */
    private final AtomicInteger currEvents = new AtomicInteger(0);

    /** Sliding-window list: head = newest bucket, tail = oldest bucket. */
    private final DoublyLinkedList<EventData> window = new DoublyLinkedList<>();

    /**
     * Pending expiry callbacks in insertion order.
     * Guarded by {@code this}.
     */
    private final List<Callback> callbacks = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates an {@code ExpiringCounter}.
     *
     * @param windowMillis positive window size in milliseconds
     * @param clock        time source; must not be {@code null}
     * @throws IllegalArgumentException if {@code windowMillis} is not positive
     * @throws NullPointerException     if {@code clock} is {@code null}
     */
    public ExpiringCounter(long windowMillis, Clock clock) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive, got: " + windowMillis);
        }
        if (clock == null) {
            throw new NullPointerException("clock must not be null");
        }
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Records one event at the current clock time.
     *
     * <p>If a bucket already exists for the current millisecond, its counter is
     * incremented. Otherwise a new bucket is created (by exactly one thread) and
     * an expiry callback is registered.
     *
     * <p>Expired callbacks are also processed as a side effect of this call.
     */
    public void record() {
        long now = clock.now();
        synchronized (this) {
            processExpiredCallbacks(now);
            DoublyLinkedList.Node<EventData> head = window.getHead();
            if (head != null && head.val.time == now) {
                // Reuse the existing bucket for this millisecond
                head.val.numOfEvents.incrementAndGet();
            } else {
                // Create a new bucket – only one thread reaches this branch at a time
                DoublyLinkedList.Node<EventData> newNode =
                        window.addToHead(new EventData(now));
                callbacks.add(new Callback(now + windowMillis, newNode));
            }
            currEvents.incrementAndGet();
        }
    }

    /**
     * Returns the number of events recorded within the last {@code windowMillis}
     * milliseconds (exclusive at the lower boundary).
     *
     * <p>Expired callbacks are also processed as a side effect of this call.
     */
    public int getCount() {
        long now = clock.now();
        synchronized (this) {
            processExpiredCallbacks(now);
            return currEvents.get();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Fires every callback whose expiry time has been reached.
     * Must be called while holding {@code this} monitor.
     *
     * @param now current clock time
     */
    private void processExpiredCallbacks(long now) {
        Iterator<Callback> iter = callbacks.iterator();
        while (iter.hasNext()) {
            Callback cb = iter.next();
            if (now >= cb.expiryTime) {
                iter.remove();
                expireNode(cb.node);
            }
        }
    }

    /**
     * Removes {@code node} and all older buckets from the list, decrementing
     * {@code currEvents} by the total number of events in the removed buckets.
     * Must be called while holding {@code this} monitor.
     */
    private void expireNode(DoublyLinkedList.Node<EventData> node) {
        // Count events in node and everything toward the tail (all older buckets)
        int count = 0;
        DoublyLinkedList.Node<EventData> curr = node;
        while (curr != null) {
            count += curr.val.numOfEvents.get();
            curr = curr.next;
        }
        window.removeAllNodes(node);
        currEvents.addAndGet(-count);
    }
}

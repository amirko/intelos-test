import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread-safe sliding-window event counter.
 *
 * <p>Events are recorded via {@link #record()} and counted via {@link #getCount()}.
 * Only events that occurred within the last {@code windowMillis} milliseconds are
 * included in the count.
 *
 * <p>Internally the counter maintains a {@link DoublyLinkedList} of time-bucketed
 * nodes (one node per distinct millisecond in which at least one event occurred).
 * The head of the list holds the most recent bucket; the tail holds the oldest.
 *
 * <p>When a new bucket is created a callback is scheduled via the {@link Scheduler}
 * to fire after {@code windowMillis} milliseconds.  The callback removes the bucket
 * and all older buckets from the list and decrements {@code currEvents} accordingly.
 */
public class ExpiringCounter {

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /** One bucket representing all events that occurred at the same millisecond. */
    static class EventData {
        final long time;
        final AtomicInteger numOfEvents;
        /** Set to {@code true} once this node has been removed by an expiry call. */
        boolean removed;

        EventData(long time) {
            this.time = time;
            this.numOfEvents = new AtomicInteger(1);
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final long windowMillis;
    private final Clock clock;
    private final Scheduler scheduler;

    /** Total number of live events, kept in sync with the DLL contents. */
    private final AtomicInteger currEvents = new AtomicInteger(0);

    /** Sliding-window list: head = newest bucket, tail = oldest bucket. */
    private final DoublyLinkedList<EventData> window = new DoublyLinkedList<>();

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Creates an {@code ExpiringCounter} with the default scheduler: each expiry
     * callback is run by a daemon thread that sleeps {@code windowMillis} milliseconds.
     *
     * @param windowMillis positive window size in milliseconds
     * @param clock        time source; must not be {@code null}
     * @throws IllegalArgumentException if {@code windowMillis} is not positive
     * @throws NullPointerException     if {@code clock} is {@code null}
     */
    public ExpiringCounter(long windowMillis, Clock clock) {
        this(windowMillis, clock, (callback, delayMs) -> {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                callback.run();
            });
            t.setDaemon(true);
            t.start();
        });
    }

    /**
     * Creates an {@code ExpiringCounter} with an explicit scheduler.
     * Use this constructor in tests to supply a fake scheduler driven by the test clock.
     *
     * @param windowMillis positive window size in milliseconds
     * @param clock        time source; must not be {@code null}
     * @param scheduler    used to schedule node-expiry callbacks; must not be {@code null}
     * @throws IllegalArgumentException if {@code windowMillis} is not positive
     * @throws NullPointerException     if {@code clock} or {@code scheduler} is {@code null}
     */
    public ExpiringCounter(long windowMillis, Clock clock, Scheduler scheduler) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be > 0, got: " + windowMillis);
        }
        if (clock == null) {
            throw new NullPointerException("clock must not be null");
        }
        if (scheduler == null) {
            throw new NullPointerException("scheduler must not be null");
        }
        this.windowMillis = windowMillis;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Records one event at the current clock time.
     *
     * <p>Uses a double-checked lock so that only one thread ever creates a new
     * bucket for a given millisecond.  If a bucket already exists for the current
     * millisecond its counter is incremented atomically without acquiring the lock
     * (fast path).
     */
    public void record() {
        long now = clock.now();

        // Fast path – optimistic read of head (volatile); no lock required when
        // a bucket for the current millisecond is already at the head.
        DoublyLinkedList.Node<EventData> head = window.getHead();
        if (head != null && head.val.time == now) {
            head.val.numOfEvents.incrementAndGet();
            currEvents.incrementAndGet();
            return;
        }

        // Slow path – a new bucket may need to be created; only one thread must do this.
        synchronized (this) {
            head = window.getHead();
            if (head != null && head.val.time == now) {
                // Another thread created the bucket while we waited for the lock.
                head.val.numOfEvents.incrementAndGet();
            } else {
                DoublyLinkedList.Node<EventData> newNode =
                        window.addToHead(new EventData(now));
                // Schedule a background thread to expire this bucket after windowMillis.
                scheduler.schedule(() -> expireNode(newNode), windowMillis);
            }
            currEvents.incrementAndGet();
        }
    }

    /**
     * Returns the number of live events (those not yet expired).
     *
     * <p>This is a lock-free read of the {@link AtomicInteger} that is kept
     * up-to-date by background expiry callbacks.
     */
    public int getCount() {
        return currEvents.get();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Removes {@code node} and all older buckets from the list, decrementing
     * {@code currEvents} by the total number of events in the removed buckets.
     *
     * <p>Idempotent: if the node was already removed by an earlier expiry call
     * (because a newer node's callback fired first and swept it away), this method
     * returns immediately.
     */
    private synchronized void expireNode(DoublyLinkedList.Node<EventData> node) {
        if (node.val.removed) {
            return;
        }
        int count = 0;
        DoublyLinkedList.Node<EventData> curr = node;
        while (curr != null) {
            curr.val.removed = true;
            count += curr.val.numOfEvents.get();
            curr = curr.next;
        }
        window.removeAllNodes(node);
        currEvents.addAndGet(-count);
    }
}

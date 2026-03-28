Create a java module that does the following:

Implement an ExpiringCounter in Java.
It supports:
- record(): record an event at the current time
-  getCount(): return number of events in the last windowMillis milliseconds

**Time Abstraction
===================
You must define a Clock interface with a single method:
public interface Clock {
long now();
}
Your ExpiringCounter must have a public constructor with this exact signature:
public ExpiringCounter(long windowMillis, Clock clock)
Tests must not depend on real time or sleep calls.
Tests must control time progression explicitly (by setting or advancing the fake clock). The clock must return
only the time assigned by the test and must not change on its own—no auto-advancing, no call-based
increments, and no randomness.

The ExpiringCounter will be used by multiple threads simultaneously and avoid race conditions.

Thread-Safety Requirements
The class will be used by multiple threads calling record() and getCount() concurrently. Your
implementation must behave correctly under such concurrent use.

**Implement as follows:
=======================

The ExpiringCounter should contain a doubly linked list as a sliding window of events. Each node in the list should be a class/record with the following data: {int time, AtomicInteger numOfEvents, prev, next}. 
Each node represents a time unit. The list's length should not exceed windowMillis.
The ExpiringCounter should have an AtomicInteger member called currEvents. The getCount should return its value.

Create a DoublyLinkedList class in java, which has nodes with <val, next, prev>
implement getHead, getTail, removeTail, removeNode, removeAllNodes(node) which will remove the node and all succeeding nodes.

When creating an event (in the record method), the currEvents should be incremented. If there is no available node for the current time, create one with numOfEvents = 1. The node should be created only once by a single thread, and the operation should be synchronized. Using a thread safe collection is not allowed. 
When creating a new node, a callback should be created to be activated at the time of expiration (current time + windowMillis). The callback will be given the node to delete, and will call removeAllNodes(node). Ideally should always remove tail, but this ensures all expired nodes will be removed.

**Testing
==========
Create the following tests using JUnit:

1. Empty counter
2. Simple recording
3. Expiration
4. Rolling window behavior
5. Boundary conditions
6. Invalid constructor arguments
7. Concurrency (threads calling both methods)
8. windowMillis=1. Both single threaded and multithreaded.
9. non-consecutive events (i.e. event at ts 1, ts 5, and nothing between).
10. Deletion of expired event from list and decrementing number of events.

Since tests change the Clock time by incrementing (and not System time), find a way to run the callback at the correct time.
tests may  depend on real time (for example System.currentTimeMillis or Thread.sleep)

Completed at: 2025-03-28 17:30

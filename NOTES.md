**Implementation Notes**

**Design**
The design is as follows:
- Usage of an AtomicInteger as a single source of truth for number of events. Yields read time of O(1).
- Usage of a doubly linked list for counting events that run in the same time. Each node represents a time unit. When recording, check if head.time == now. If not - create it. This is the only place in the code that uses synchronization.
- When recording, a new Runnable task is created. It sleeps for the window time. *Note*: I noticed only now that the counter is incremented only after checking/creating a new time node. It should be done at the first line, as it is always necessary. Complexity: O(1).
- Memory complexity: O(n), where n is the window size. A maximum of n nodes is needed to store all running events.
-  Expiration complexity: amortized time of O(1), can be O(n) in edge cases when deleting a non-tail node, which incurs iteration of succeeding nodes. The callback thead has a reference to the node, so no need to search for it in the list. O(1).
-  count() complexity = O(1).
-  record() complexity = O(1).

**Usage of AI**
I declare using github Copilot for the task, for implementation only. No use of AI for design.

**Testing**
I applied all required tests, and added some cases. I also added multi-threaded tests. However, I noticed that the multi-threaded tests concentrate mostly on concurrent writes. I should've tested concurrent reads as well, as most of the concurrency is in reading.

*Note: I would've written all the required sections of this document and write a more orderly design document if I had more time.

/**
 * A generic doubly linked list.
 *
 * <p>The head holds the most recently inserted element; the tail holds the oldest.
 * {@code next} points from head toward tail (older direction);
 * {@code prev} points from tail toward head (newer direction).
 *
 * @param <T> the type of element stored in each node
 */
public class DoublyLinkedList<T> {

    /**
     * A node in the doubly linked list.
     */
    public static class Node<T> {
        T val;
        Node<T> next; // toward tail (older)
        Node<T> prev; // toward head (newer)

        Node(T val) {
            this.val = val;
        }
    }

    // volatile so that the lock-free fast path in ExpiringCounter.record() always
    // sees the most recently written head without entering a synchronized block.
    private volatile Node<T> head;
    private Node<T> tail;

    /** Returns the head (most-recently added) node, or {@code null} if the list is empty. */
    public Node<T> getHead() {
        return head;
    }

    /** Returns the tail (oldest) node, or {@code null} if the list is empty. */
    public Node<T> getTail() {
        return tail;
    }

    /**
     * Removes and disconnects the tail node. No-op if the list is empty.
     */
    public void removeTail() {
        if (tail == null) {
            return;
        }
        if (tail == head) {
            head = null;
            tail = null;
        } else {
            Node<T> newTail = tail.prev;
            newTail.next = null;
            tail.prev = null;
            tail = newTail;
        }
    }

    /**
     * Removes a single {@code node} from the list. No-op if {@code node} is {@code null}.
     */
    public void removeNode(Node<T> node) {
        if (node == null) {
            return;
        }
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.next = null;
        node.prev = null;
    }

    /**
     * Removes {@code node} and every node that follows it toward the tail
     * (i.e., {@code node}, {@code node.next}, {@code node.next.next}, …).
     *
     * <p>No-op if {@code node} is {@code null}.
     */
    public void removeAllNodes(Node<T> node) {
        if (node == null) {
            return;
        }
        if (node.prev != null) {
            // node.prev becomes the new tail
            node.prev.next = null;
            tail = node.prev;
        } else {
            // node is (or was) the head – clear the whole list
            head = null;
            tail = null;
        }
        node.prev = null;
        // node and everything reachable via node.next are now orphaned
    }

    /**
     * Prepends a new node with value {@code val} at the head and returns it.
     */
    public Node<T> addToHead(T val) {
        Node<T> node = new Node<>(val);
        if (head == null) {
            head = node;
            tail = node;
        } else {
            node.next = head;
            head.prev = node;
            head = node;
        }
        return node;
    }
}

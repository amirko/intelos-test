package com.intelos;

/**
 * Abstraction for the current time, enabling deterministic testing.
 */
public interface Clock {
    /**
     * Returns the current time in milliseconds.
     */
    long now();
}

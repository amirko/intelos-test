/**
 * Strategy for scheduling a one-shot callback to run after a given delay.
 *
 * <p>The default production implementation spawns a daemon thread that sleeps
 * for {@code delayMs} milliseconds and then invokes the callback.  Tests may
 * supply a fake implementation that fires callbacks when the test clock is
 * manually advanced.
 */
@FunctionalInterface
public interface Scheduler {
    /**
     * Schedules {@code callback} to be invoked after {@code delayMs} milliseconds.
     *
     * @param callback the task to execute
     * @param delayMs  delay in milliseconds (must be positive)
     */
    void schedule(Runnable callback, long delayMs);
}

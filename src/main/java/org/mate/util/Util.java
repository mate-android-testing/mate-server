package org.mate.util;

/**
 * Provides a set of utility functions.
 */
public final class Util {

    private Util() {
        throw new UnsupportedOperationException("Cannot instantiate utility class!");
    }

    /**
     * Suspends the current thread for the specified number of seconds.
     *
     * @param seconds The seconds the current thread should sleep.
     */
    public static void sleep(final long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (final InterruptedException e) {
            Log.printWarning("Interrupted during sleep");
            Log.printWarning(e.getMessage());
            throw new RuntimeException(e);
        }
    }
}


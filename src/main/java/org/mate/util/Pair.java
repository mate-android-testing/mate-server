package org.mate.util;

import java.util.Objects;

/**
 * This class holds a pair of objects.
 */
public final class Pair<T, U> {

    private final T fst;
    private final U snd;

    public Pair(final T fst, final U snd) {
        this.fst = fst;
        this.snd = snd;
    }

    public T fst() {
        return this.fst;
    }

    public U snd() {
        return this.snd;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof Pair)) {
            return false;
        } else {
            final var o = (Pair<?, ?>) other;
            return Objects.equals(this.fst, o.fst) && Objects.equals(this.snd, o.snd);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(fst, snd);
    }
}


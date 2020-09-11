package org.mate.util;

import java.util.Optional;

/**
 * Can either represent a success or a failure value. Should be used as a return type when a function can either
 * yield a result or fail with a message or an error code, for example.
 */
public abstract class Result<S, T> {
    public abstract boolean isOk();
    public abstract boolean isErr();
    public abstract Optional<S> toOk();
    public abstract Optional<T> toErr();
    public abstract S getOk();
    public abstract T getErr();
    public static <S, T> Result<S, T> okOf(S value) {
        return new Ok<>(value);
    }

    public static <S, T> Result<S, T> errOf(T value) {
        return new Err<>(value);
    }

    private static class Ok<U, V> extends Result<U, V> {
        private final U value;
        public Ok(U value) {
            this.value = value;
        }

        @Override
        public boolean isOk() {
            return true;
        }

        @Override
        public boolean isErr() {
            return false;
        }

        @Override
        public Optional<U> toOk() {
            return Optional.of(value);
        }

        @Override
        public Optional<V> toErr() {
            return Optional.empty();
        }

        @Override
        public U getOk() {
            return value;
        }

        @Override
        public V getErr() {
            throw new IllegalStateException("this results does not represent an error value");
        }
    }

    private static class Err<U, V> extends Result<U, V> {
        private final V value;
        public Err(V value) {
            this.value = value;
        }

        @Override
        public boolean isOk() {
            return false;
        }

        @Override
        public boolean isErr() {
            return true;
        }

        @Override
        public Optional<U> toOk() {
            return Optional.empty();
        }

        @Override
        public Optional<V> toErr() {
            return Optional.of(value);
        }

        @Override
        public U getOk() {
            throw new IllegalStateException("this results does not represent an ok value");
        }

        @Override
        public V getErr() {
            return value;
        }
    }
}

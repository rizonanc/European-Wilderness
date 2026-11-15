package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Priority shared by every reader of one tile flight. */
public final class InferenceDemand {
    public enum Priority {
        FOREGROUND,
        NEAR,
        DISTANT
    }

    private static final ThreadLocal<InferenceDemand> CURRENT = new ThreadLocal<>();
    private volatile Priority priority;
    private final java.util.Set<Consumer<Priority>> listeners = new java.util.HashSet<>();

    public InferenceDemand(Priority priority) {
        this.priority = java.util.Objects.requireNonNull(priority);
    }

    public Priority priority() {
        return priority;
    }

    public static Priority currentPriority() {
        var value = CURRENT.get();
        return value == null ? Priority.FOREGROUND : value.priority();
    }

    public static InferenceDemand current() {
        var value = CURRENT.get();
        return value == null ? new InferenceDemand(Priority.FOREGROUND) : value;
    }

    public void promote(Priority requested) {
        java.util.List<Consumer<Priority>> callbacks;
        synchronized (this) {
            if (requested.ordinal() >= priority.ordinal()) return;
            priority = requested;
            callbacks = java.util.List.copyOf(listeners);
        }
        for (var callback : callbacks) callback.accept(requested);
    }

    Scope listen(Consumer<Priority> callback) {
        Priority value;
        synchronized (this) {
            listeners.add(callback);
            value = priority;
        }
        callback.accept(value);
        return () -> {
            synchronized (this) {
                listeners.remove(callback);
            }
        };
    }

    public Scope inheritPriorityFrom(InferenceDemand parent) {
        return parent == this ? () -> {} : parent.listen(this::promote);
    }

    public static Scope scope(Priority priority) {
        return scope(new InferenceDemand(priority));
    }

    private static Scope scope(InferenceDemand demand) {
        var previous = CURRENT.get();
        CURRENT.set(demand);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static <T> T call(InferenceDemand demand, Callable<T> work) throws Exception {
        try (var ignored = scope(demand)) {
            return work.call();
        }
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

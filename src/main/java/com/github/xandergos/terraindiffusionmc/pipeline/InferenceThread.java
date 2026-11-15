package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage.*;

/** One native inference owner. Admission bounds the existing queue, never runs work on callers. */
public final class InferenceThread implements AutoCloseable {
    private static final long DEFAULT_BYTES = 4096, DEFAULT_LIMIT = 64L * 1024 * 1024;
    private static final long REQUIRED_AGING = TimeUnit.SECONDS.toNanos(10),
            OPTIONAL_LIFETIME = TimeUnit.SECONDS.toNanos(2);
    private final ThreadPoolExecutor executor;
    private final Object lock = new Object();
    private final Set<Job<?>> live = new HashSet<>();
    private final Set<Admission> waiters = new LinkedHashSet<>();
    private final int maxJobs, reservedForeground;
    private final long maxBytes;
    private final LongSupplier clock;
    private final AtomicLong sequence = new AtomicLong(),
            promoted = new AtomicLong(),
            stale = new AtomicLong(),
            deferred = new AtomicLong();
    private final StageTimings timings = new StageTimings();
    private long pendingBytes, highWaterBytes;
    private int highWaterJobs, active;
    private boolean closed;
    private volatile Thread worker;

    public InferenceThread(String threadName) {
        this(threadName, 64, DEFAULT_LIMIT, 8);
    }

    InferenceThread(String threadName, int maxJobs, long maxBytes, int reservedForeground) {
        this(threadName, maxJobs, maxBytes, reservedForeground, System::nanoTime);
    }

    InferenceThread(String threadName, int maxJobs, long maxBytes, int reservedForeground, LongSupplier clock) {
        if (maxJobs < 2 || maxBytes < 2 || reservedForeground < 1 || reservedForeground >= maxJobs)
            throw new IllegalArgumentException("Invalid inference admission limits");
        this.maxJobs = maxJobs;
        this.maxBytes = maxBytes;
        this.reservedForeground = reservedForeground;
        this.clock = Objects.requireNonNull(clock);
        executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new PriorityBlockingQueue<>(),
                        task -> {
                            var thread = new Thread(task, threadName);
                            thread.setDaemon(true);
                            thread.setUncaughtExceptionHandler(
                                    (t, failure) -> failed(Code.SCH_WORKER, "INFERENCE_WORKER", failure));
                            worker = thread;
                            return thread;
                        });
    }

    public <T> Future<T> submit(Callable<T> task) {
        return submit(InferenceDemand.current(), () -> true, DEFAULT_BYTES, task);
    }

    public <T> Future<T> submit(InferenceDemand demand, BooleanSupplier valid, Callable<T> task) {
        return submit(demand, valid, DEFAULT_BYTES, task);
    }

    public <T> Future<T> submit(InferenceDemand demand, BooleanSupplier valid, long estimatedBytes, Callable<T> task) {
        return admit(demand, valid, estimatedBytes, false, task);
    }

    /** Only disposable, unshared previews use this path. Raw tiles/water dependencies remain required at every priority. */
    public <T> Future<T> submitOptional(
            InferenceDemand demand, BooleanSupplier valid, long estimatedBytes, Callable<T> task) {
        return admit(demand, valid, estimatedBytes, true, task);
    }

    private <T> Future<T> admit(
            InferenceDemand demand, BooleanSupplier valid, long bytes, boolean optional, Callable<T> task) {
        Objects.requireNonNull(demand);
        Objects.requireNonNull(valid);
        Objects.requireNonNull(task);
        if (bytes < 1) throw new IllegalArgumentException("Positive pending-byte estimate required");
        if (Thread.currentThread() == worker)
            throw new RejectedExecutionException("Nested inference submission would deadlock its single owner");
        var request = new Admission(demand, bytes, clock.getAsLong(), sequence.getAndIncrement());
        boolean interrupted = false, reportedWait = false;
        var diagnostic = current("INFERENCE_ADMISSION");
        synchronized (lock) {
            if (closed) throw new RejectedExecutionException("Inference closed");
            if (!optional) waiters.add(request);
        }
        try {
            while (true) {
                if (!reportedWait && clock.getAsLong() - request.createdAt >= TimeUnit.SECONDS.toNanos(30)) {
                    reportedWait = true;
                    emit(
                            Code.SCH_PRESSURE,
                            diagnostic,
                            null,
                            Outcome.DEGRADED,
                            "Required work still waiting; not dropped. See inference admission counters",
                            false);
                }
                discardStale();
                if (!valid.getAsBoolean()) {
                    stale.incrementAndGet();
                    return CompletableFuture.failedFuture(new CancellationException("Retired inference session"));
                }
                if (!optional && clock.getAsLong() - request.createdAt >= REQUIRED_AGING)
                    demand.promote(InferenceDemand.Priority.FOREGROUND);
                synchronized (lock) {
                    if (closed) throw new RejectedExecutionException("Inference closed");
                    if (hasCapacity(request, optional) && turn(request)) {
                        var job = new Job<T>(request, valid, optional, task, diagnostic.stage("INFERENCE_TASK"));
                        live.add(job);
                        pendingBytes += bytes;
                        highWaterJobs = Math.max(highWaterJobs, live.size());
                        highWaterBytes = Math.max(highWaterBytes, pendingBytes);
                        job.subscription = demand.listen(job::promote);
                        try {
                            executor.execute(job);
                        } catch (RuntimeException failure) {
                            job.cancel(false);
                            throw failure;
                        }
                        timings.record("admission." + demand.priority(), clock.getAsLong() - request.createdAt);
                        return job;
                    }
                    if (optional) {
                        deferred.incrementAndGet();
                        return CompletableFuture.failedFuture(new OptionalDeferredException());
                    }
                    try {
                        lock.wait(100);
                    } catch (InterruptedException cancelled) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            synchronized (lock) {
                waiters.remove(request);
                lock.notifyAll();
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean hasCapacity(Admission request, boolean optional) {
        boolean foreground = request.demand.priority() == InferenceDemand.Priority.FOREGROUND;
        int jobs = foreground ? maxJobs : maxJobs - reservedForeground;
        long bytes = foreground ? maxBytes : maxBytes - maxBytes * reservedForeground / maxJobs;
        // An oversized required request must still progress, alone; native working memory is not this estimate.
        return live.size() < jobs
                && (request.bytes <= bytes ? pendingBytes <= bytes - request.bytes : !optional && live.isEmpty());
    }

    private boolean turn(Admission request) {
        var priority = request.demand.priority();
        for (var earlier : waiters) {
            int rank = Integer.compare(earlier.demand.priority().ordinal(), priority.ordinal());
            if (rank < 0 || rank == 0 && earlier.order < request.order) return false;
        }
        return true;
    }

    public void discardStale() {
        List<Job<?>> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(live);
        }
        long now = clock.getAsLong();
        for (var job : snapshot) {
            if (job.started || job.isDone()) continue;
            try {
                if (!job.valid.getAsBoolean()
                        || job.optional
                                && job.demand.priority() != InferenceDemand.Priority.FOREGROUND
                                && now - job.createdAt >= OPTIONAL_LIFETIME) job.retire();
                else if (!job.optional && now - job.createdAt >= REQUIRED_AGING)
                    job.demand.promote(InferenceDemand.Priority.FOREGROUND);
            } catch (RuntimeException failure) {
                job.failBeforeStart(failure);
            }
        }
    }

    private record Admission(InferenceDemand demand, long bytes, long createdAt, long order) {}

    public static final class OptionalDeferredException extends CancellationException {
        OptionalDeferredException() {
            super("Optional inference deferred by admission budget");
        }
    }

    private final class Job<T> extends FutureTask<T> implements Comparable<Job<?>> {
        final InferenceDemand demand;
        final BooleanSupplier valid;
        final long order, bytes, createdAt;
        final boolean optional;
        volatile boolean started;
        volatile InferenceDemand.Priority priority;
        InferenceDemand.Scope subscription;

        Job(Admission request, BooleanSupplier valid, boolean optional, Callable<T> work, Context context) {
            super(() -> {
                try (var ignored = DiagnosticErrors.scope(context)) {
                    if (!valid.getAsBoolean()) throw new CancellationException("Retired inference session");
                    try {
                        return InferenceDemand.call(request.demand, work);
                    } catch (Exception | Error failure) {
                        failed(Code.SCH_TASK, "INFERENCE_TASK", failure);
                        throw failure;
                    }
                }
            });
            demand = request.demand;
            this.valid = valid;
            this.optional = optional;
            order = request.order;
            bytes = request.bytes;
            createdAt = request.createdAt;
            priority = demand.priority();
        }

        void promote(InferenceDemand.Priority requested) {
            synchronized (lock) {
                if (isDone() || requested.ordinal() >= priority.ordinal()) return;
                boolean waiting = executor.getQueue().remove(this);
                priority = requested;
                if (waiting) {
                    executor.getQueue().offer(this);
                    promoted.incrementAndGet();
                }
                lock.notifyAll();
            }
        }

        void retire() {
            synchronized (lock) {
                if (!started && !isDone()) {
                    stale.incrementAndGet();
                    setException(new CancellationException("Retired or expired inference request"));
                }
            }
        }

        void failBeforeStart(RuntimeException failure) {
            synchronized (lock) {
                if (!started && !isDone()) setException(failure);
            }
        }

        @Override
        public int compareTo(Job<?> other) {
            int rank = Integer.compare(priority.ordinal(), other.priority.ordinal());
            return rank != 0 ? rank : Long.compare(order, other.order);
        }

        @Override
        public void run() {
            discardStale();
            synchronized (lock) {
                if (isDone()) return;
                started = true;
                active++;
            }
            long start = clock.getAsLong();
            var startedPriority = priority;
            timings.record("queue." + startedPriority, start - createdAt);
            PerformanceDiagnostics.record(INFERENCE_QUEUE, createdAt, start);
            long trace = PerformanceDiagnostics.begin(INFERENCE_EXECUTE);
            try {
                super.run();
            } finally {
                PerformanceDiagnostics.end(trace);
                timings.record("execute." + startedPriority, clock.getAsLong() - start);
                synchronized (lock) {
                    active--;
                    release();
                }
            }
        }

        @Override
        protected void done() {
            synchronized (lock) {
                if (subscription != null) subscription.close();
                if (!started) release();
            }
        }

        private void release() {
            executor.getQueue().remove(this);
            if (live.remove(this)) pendingBytes -= bytes;
            lock.notifyAll();
        }

        // Native Run is not interrupted: a cancelled running task stays charged until run() actually returns.
        @Override
        public boolean cancel(boolean interrupt) {
            return super.cancel(false);
        }
    }

    public record Snapshot(
            int pendingJobs,
            long pendingBytes,
            int queuedJobs,
            int activeJobs,
            int highWaterJobs,
            long highWaterBytes,
            int admissionWaiters,
            long admissionWaitBytes,
            long oldestForegroundWaitNanos,
            long oldestForegroundAgeNanos,
            long promoted,
            long stale,
            long optionalDeferred,
            int jobLimit,
            long estimatedByteLimit,
            Map<String, StageTimings.Snapshot> timings) {}

    public boolean foregroundPending() {
        synchronized (lock) {
            for (var job : live) if (job.demand.priority() == InferenceDemand.Priority.FOREGROUND) return true;
            for (var request : waiters)
                if (request.demand.priority() == InferenceDemand.Priority.FOREGROUND) return true;
            return false;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            long oldest = 0, oldestAge = 0, waitingBytes = 0, now = clock.getAsLong();
            for (var job : live)
                if (job.demand.priority() == InferenceDemand.Priority.FOREGROUND) {
                    oldestAge = Math.max(oldestAge, now - job.createdAt);
                    if (!job.started) oldest = Math.max(oldest, now - job.createdAt);
                }
            for (var request : waiters) {
                waitingBytes += request.bytes;
                if (request.demand.priority() == InferenceDemand.Priority.FOREGROUND)
                    oldest = Math.max(oldest, now - request.createdAt);
            }
            oldestAge = Math.max(oldestAge, oldest);
            return new Snapshot(
                    live.size(),
                    pendingBytes,
                    executor.getQueue().size(),
                    active,
                    highWaterJobs,
                    highWaterBytes,
                    waiters.size(),
                    waitingBytes,
                    oldest,
                    oldestAge,
                    promoted.get(),
                    stale.get(),
                    deferred.get(),
                    maxJobs,
                    maxBytes,
                    timings.snapshot());
        }
    }

    public String timings() {
        var s = snapshot();
        return s.timings()
                + " waiting="
                + s.queuedJobs()
                + " promoted="
                + s.promoted()
                + " stale="
                + s.stale()
                + " pending="
                + s.pendingJobs()
                + "/"
                + s.jobLimit()
                + " bytesEstimate="
                + s.pendingBytes()
                + "/"
                + s.estimatedByteLimit()
                + " highWater="
                + s.highWaterJobs()
                + "/"
                + s.highWaterBytes()
                + " admissionWaiters="
                + s.admissionWaiters()
                + " oldestForegroundWaitNs="
                + s.oldestForegroundWaitNanos()
                + " oldestForegroundAgeNs="
                + s.oldestForegroundAgeNanos();
    }

    public Future<?> submit(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }

    public void drain() throws InterruptedException, ExecutionException {
        List<Job<?>> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(live);
        }
        for (var job : snapshot)
            try {
                job.get();
            } catch (CancellationException ignored) {
            }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            for (var job : List.copyOf(live)) if (!job.started) job.cancel(false);
            executor.shutdown();
            lock.notifyAll();
        }
    }
}

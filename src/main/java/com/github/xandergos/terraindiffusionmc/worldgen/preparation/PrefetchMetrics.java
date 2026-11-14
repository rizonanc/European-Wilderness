package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import java.util.concurrent.atomic.AtomicLong;

public final class PrefetchMetrics {
   private final AtomicLong started = new AtomicLong();
   private final AtomicLong completed = new AtomicLong();
   private final AtomicLong readySkips = new AtomicLong();
   private final AtomicLong failures = new AtomicLong();
   private final AtomicLong foregroundDeferrals = new AtomicLong();

   void start() {
      this.started.incrementAndGet();
   }

   void complete() {
      this.completed.incrementAndGet();
   }

   void readySkip() {
      this.readySkips.incrementAndGet();
   }

   void failure() {
      this.failures.incrementAndGet();
   }

   void deferForeground() {
      this.foregroundDeferrals.incrementAndGet();
   }

   public PrefetchMetrics.Snapshot snapshot() {
      return new PrefetchMetrics.Snapshot(this.started.get(), this.completed.get(), this.readySkips.get(), this.failures.get(), this.foregroundDeferrals.get());
   }

   public record Snapshot(long started, long completed, long readySkips, long failures, long foregroundDeferrals) {
      @Override public String toString(){return "stagesStarted="+started+" targetsCompleted="+completed+" readySkips="+readySkips
         +" failures="+failures+" foregroundDeferrals="+foregroundDeferrals;}
   }
}

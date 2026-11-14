package com.github.xandergos.terraindiffusionmc.worldgen.settings;

public final class PerformanceProfile {
   public static final int MIN_MEMORY_BUDGET_MB = 512;
   public static final int MAX_MEMORY_BUDGET_MB = 65536;
   public static final int MIN_QUEUE_DEPTH = 1;
   public static final int MAX_QUEUE_DEPTH = 1024;
   public static final int MIN_DISK_QUOTA_MB = 1024;
   public static final int MAX_DISK_QUOTA_MB = 1048576;
   private final int memoryBudgetMb;
   private final int maxQueueDepth;
   private final int diskQuotaMb;
   private final PerformanceProfile.PreparationMode preparationMode;

   private PerformanceProfile(int memoryBudgetMb, int maxQueueDepth, int diskQuotaMb, PerformanceProfile.PreparationMode preparationMode) {
      if (memoryBudgetMb < 512 || memoryBudgetMb > 65536) {
         throw new IllegalArgumentException("memoryBudgetMb must be 512..65536, got " + memoryBudgetMb);
      }

      if (maxQueueDepth < 1 || maxQueueDepth > 1024) {
         throw new IllegalArgumentException("maxQueueDepth must be 1..1024, got " + maxQueueDepth);
      }

      if (diskQuotaMb < 1024 || diskQuotaMb > 1048576) {
         throw new IllegalArgumentException("diskQuotaMb must be 1024..1048576, got " + diskQuotaMb);
      }

      if (preparationMode == null) {
         throw new IllegalArgumentException("preparationMode must not be null");
      }

      this.memoryBudgetMb = memoryBudgetMb;
      this.maxQueueDepth = maxQueueDepth;
      this.diskQuotaMb = diskQuotaMb;
      this.preparationMode = preparationMode;
   }

   public static PerformanceProfile of(int memoryBudgetMb, int maxQueueDepth, int diskQuotaMb, PerformanceProfile.PreparationMode preparationMode) {
      return new PerformanceProfile(memoryBudgetMb, maxQueueDepth, diskQuotaMb, preparationMode);
   }

   public static PerformanceProfile defaults() {
      return new PerformanceProfile(4096, 8, 10240, PerformanceProfile.PreparationMode.ON_DEMAND);
   }

   public int memoryBudgetMb() {
      return this.memoryBudgetMb;
   }

   public int maxQueueDepth() {
      return this.maxQueueDepth;
   }

   public int diskQuotaMb() {
      return this.diskQuotaMb;
   }

   public PerformanceProfile.PreparationMode preparationMode() {
      return this.preparationMode;
   }

   @Override
   public String toString() {
      return "PerformanceProfile[memory="
         + this.memoryBudgetMb
         + "MB, queue="
         + this.maxQueueDepth
         + ", diskQuota="
         + this.diskQuotaMb
         + "MB, mode="
         + this.preparationMode
         + "]";
   }

   public enum PreparationMode {
      ON_DEMAND,
      PREPARED_ONLY;
   }
}

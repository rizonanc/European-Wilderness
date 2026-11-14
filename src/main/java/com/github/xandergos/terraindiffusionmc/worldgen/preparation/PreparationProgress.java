package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

public final class PreparationProgress {
   private final PreparationProgress.State state;
   private final int totalTiles;
   private final int completedTiles;
   private final int skippedPresentTiles;
   private final int failedTiles;
   private final long startedAtMillis;
   private final long finishedAtMillis;
   private final String lastError;
   private final String currentKey;

   public PreparationProgress(
      PreparationProgress.State state,
      int totalTiles,
      int completedTiles,
      int skippedPresentTiles,
      int failedTiles,
      long startedAtMillis,
      long finishedAtMillis,
      String lastError,
      String currentKey
   ) {
      this.state = state;
      this.totalTiles = totalTiles;
      this.completedTiles = completedTiles;
      this.skippedPresentTiles = skippedPresentTiles;
      this.failedTiles = failedTiles;
      this.startedAtMillis = startedAtMillis;
      this.finishedAtMillis = finishedAtMillis;
      this.lastError = lastError;
      this.currentKey = currentKey;
   }

   public static PreparationProgress idle() {
      return new PreparationProgress(PreparationProgress.State.IDLE, 0, 0, 0, 0, 0L, 0L, null, null);
   }

   public PreparationProgress.State state() {
      return this.state;
   }

   public int totalTiles() {
      return this.totalTiles;
   }

   public int completedTiles() {
      return this.completedTiles;
   }

   public int skippedPresentTiles() {
      return this.skippedPresentTiles;
   }

   public int failedTiles() {
      return this.failedTiles;
   }

   public int doneTiles() {
      return this.completedTiles + this.skippedPresentTiles;
   }

   public long startedAtMillis() {
      return this.startedAtMillis;
   }

   public long finishedAtMillis() {
      return this.finishedAtMillis;
   }

   public String lastError() {
      return this.lastError;
   }

   public String currentKey() {
      return this.currentKey;
   }

   public long elapsedMs() {
      if (this.startedAtMillis == 0L) {
         return 0L;
      }

      long end = this.finishedAtMillis != 0L ? this.finishedAtMillis : System.currentTimeMillis();
      return Math.max(0L, end - this.startedAtMillis);
   }

   public Long estimatedRemainingMs() {
      if (this.startedAtMillis == 0L) {
         return null;
      }

      int done = this.doneTiles();
      int remaining = this.totalTiles - done - this.failedTiles;
      if (remaining <= 0) {
         return 0L;
      }

      if (done < 3) {
         return null;
      }

      long elapsed = this.elapsedMs();
      return elapsed * remaining / done;
   }

   public boolean isFinished() {
      return this.state == PreparationProgress.State.CANCELLED
         || this.state == PreparationProgress.State.DONE
         || this.state == PreparationProgress.State.FAILED;
   }

   @Override
   public String toString() {
      Long eta = this.estimatedRemainingMs();
      return "Preparation["
         + this.state
         + " "
         + this.doneTiles()
         + "/"
         + this.totalTiles
         + " (computed "
         + this.completedTiles
         + ", present "
         + this.skippedPresentTiles
         + ", failed "
         + this.failedTiles
         + "), elapsed "
         + this.elapsedMs()
         + "ms"
         + (eta == null ? "" : ", eta ~" + eta + "ms")
         + (this.lastError == null ? "" : ", error: " + this.lastError)
         + "]";
   }

   public enum State {
      IDLE,
      RUNNING,
      CANCELLED,
      DONE,
      FAILED;
   }
}

package com.github.xandergos.terraindiffusionmc.world;

import java.util.concurrent.atomic.AtomicReference;

public final class WorldScaleSelectionState {
   private static final AtomicReference<Integer> PENDING_SCALE = new AtomicReference<>();

   private WorldScaleSelectionState() {
   }

   public static void setPendingScale(int selectedScale) {
      PENDING_SCALE.set(WorldScaleManager.clampScale(selectedScale));
   }

   public static Integer consumePendingScale() {
      return PENDING_SCALE.getAndSet(null);
   }

   public static int getPendingScaleOrDefault() {
      Integer pendingScale = PENDING_SCALE.get();
      return pendingScale != null ? pendingScale : 2;
   }
}

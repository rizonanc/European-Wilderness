package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.Arrays;
import java.util.List;

public enum TreeAge {
   YOUNG(new TreeAge.Footprint(1, 1), new TreeAge.Footprint(1, 2)),
   ADULT(new TreeAge.Footprint(1, 2), new TreeAge.Footprint(2, 2), new TreeAge.Footprint(2, 3)),
   OLD(new TreeAge.Footprint(2, 3), new TreeAge.Footprint(3, 3), new TreeAge.Footprint(3, 4)),
   RARE_LARGE(new TreeAge.Footprint(3, 4), new TreeAge.Footprint(4, 4));

   private final List<TreeAge.Footprint> footprints;

   TreeAge(TreeAge.Footprint... footprints) {
      this.footprints = List.copyOf(Arrays.asList(footprints));
   }

   public List<TreeAge.Footprint> footprints() {
      return this.footprints;
   }

   public record Footprint(int width, int depth) {
      public Footprint(int width, int depth) {
         if (width >= 1 && width <= 4 && depth >= 1 && depth <= 4) {
            this.width = width;
            this.depth = depth;
         } else {
            throw new IllegalArgumentException("footprint dimensions must be between 1 and 4");
         }
      }

      public TreeAge.Footprint rotated() {
         return new TreeAge.Footprint(this.depth, this.width);
      }
   }
}

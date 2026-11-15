package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class PassCampPlanCache {
    private static final int MAX_TILES = 64;
    private final Map<TerrainTileKey, PassCampPlanCache.Plan> entries = new LinkedHashMap<>(16, 0.75F, true);

    public synchronized PassCampPlanCache.Plan getOrCompute(
            TerrainTileKey key, Supplier<PassCampPlanCache.Plan> computer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(computer, "computer");
        PassCampPlanCache.Plan cached = this.entries.get(key);
        if (cached != null) {
            return cached;
        }

        PassCampPlanCache.Plan computed = Objects.requireNonNull(computer.get(), "computed plan");
        this.entries.put(key, computed);
        if (this.entries.size() > 64) {
            this.entries.remove(this.entries.keySet().iterator().next());
        }

        return computed;
    }

    public synchronized int entryCount() {
        return this.entries.size();
    }

    public record Plan(Optional<MountainPassPlanner.PassPlan> pass, Optional<AbandonedCampPlanner.CampPlan> camp) {
        public Plan {
            Objects.requireNonNull(pass, "pass");
            Objects.requireNonNull(camp, "camp");
            if (pass.isEmpty() && camp.isPresent()) {
                throw new IllegalArgumentException("A camp needs an accepted pass");
            }
        }
    }
}

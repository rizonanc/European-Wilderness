package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainRegionCache;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.PassCampDecisionStore;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.PassCampPlanCache;
import com.github.xandergos.terraindiffusionmc.worldgen.preparation.PreparationService;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.PreparedWaterController;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.WorldWaterController;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class WorldSession {
    private static final AtomicReference<WorldSession> ACTIVE = new AtomicReference<>();
    private final UUID id;
    private volatile String diagnosticDimension = "unknown";

    public String diagnosticDimension() {
        return diagnosticDimension;
    }

    public void diagnosticDimension(String value) {
        diagnosticDimension = Objects.requireNonNull(value);
    }

    private final GenerationProfile profile;
    private final String fingerprint;
    private final long worldSeed;
    private final long createdAtMillis;
    private final MountainRegionCache mountainRegions = new MountainRegionCache();
    private final PassCampPlanCache passCampPlans = new PassCampPlanCache();
    private volatile TerrainRepository repository;
    private volatile PassCampDecisionStore passCampDecisions;
    private volatile WorldSession.RiverOverlay riverOverlay;
    private volatile PreparationService preparation;
    private volatile PreparedWaterController preparedWater;
    private volatile WorldWaterController automaticWater;
    private volatile java.util.function.BooleanSupplier backgroundAllowed = () -> true, backgroundClaim = () -> true;

    public void attachBackgroundPolicy(
            java.util.function.BooleanSupplier allowed, java.util.function.BooleanSupplier claim) {
        backgroundAllowed = Objects.requireNonNull(allowed);
        backgroundClaim = Objects.requireNonNull(claim);
    }

    public boolean backgroundAllowed() {
        return isActive() && backgroundAllowed.getAsBoolean();
    }

    public boolean claimBackgroundStep() {
        return backgroundAllowed() && backgroundClaim.getAsBoolean();
    }

    public WorldWaterController automaticWater() {
        return automaticWater;
    }

    public void attachAutomaticWater(WorldWaterController water) {
        this.automaticWater = Objects.requireNonNull(water);
        attachRiverOverlay(water);
    }

    public PreparedWaterController preparedWater() {
        return preparedWater;
    }

    public void attachPreparedWater(PreparedWaterController water) {
        this.preparedWater = Objects.requireNonNull(water);
        attachRiverOverlay(water);
    }

    private WorldSession(UUID id, GenerationProfile profile, long worldSeed) {
        this.id = Objects.requireNonNull(id, "id");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.fingerprint = profile.fingerprint();
        this.worldSeed = worldSeed;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public static WorldSession activate(GenerationProfile profile) {
        return activate(profile, 0L);
    }

    public static WorldSession activate(GenerationProfile profile, long worldSeed) {
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }

        WorldSession session = new WorldSession(UUID.randomUUID(), profile, worldSeed);
        ACTIVE.set(session);
        return session;
    }

    public static void deactivate(WorldSession session) {
        if (session != null) {
            ACTIVE.compareAndSet(session, null);
        }
    }

    public static WorldSession current() {
        return ACTIVE.get();
    }

    public static final class RetiredSessionException extends IllegalStateException {
        private RetiredSessionException() {
            super("Terrain task belongs to a retired world session; result discarded");
        }
    }

    public static void requireActive(WorldSession ticket) {
        if (ticket == null) throw new IllegalStateException("No terrain world session is bound");
        if (ACTIVE.get() != ticket) {
            throw new RetiredSessionException();
        }
    }

    public boolean isActive() {
        return ACTIVE.get() == this;
    }

    public UUID id() {
        return this.id;
    }

    public GenerationProfile profile() {
        return this.profile;
    }

    public String fingerprint() {
        return this.fingerprint;
    }

    public long worldSeed() {
        return this.worldSeed;
    }

    public long createdAtMillis() {
        return this.createdAtMillis;
    }

    public MountainRegionCache mountainRegions() {
        return this.mountainRegions;
    }

    public PassCampPlanCache passCampPlans() {
        return this.passCampPlans;
    }

    public void attachRiverOverlay(WorldSession.RiverOverlay overlay) {
        this.riverOverlay = Objects.requireNonNull(overlay, "overlay");
    }

    public void clearRiverOverlay() {
        this.riverOverlay = null;
    }

    public WorldSession.RiverOverlay riverOverlay() {
        return this.riverOverlay;
    }

    public void attachRepository(TerrainRepository repository) {
        this.repository = repository;
    }

    public TerrainRepository repository() {
        return this.repository;
    }

    public void attachPassCampDecisions(PassCampDecisionStore decisions) {
        this.passCampDecisions = Objects.requireNonNull(decisions, "decisions");
    }

    public PassCampDecisionStore passCampDecisions() {
        return this.passCampDecisions;
    }

    public void attachPreparation(PreparationService preparation) {
        this.preparation = preparation;
    }

    public PreparationService preparation() {
        return this.preparation;
    }

    public interface RiverOverlay {
        default java.util.List<com.github.xandergos.terraindiffusionmc.worldgen.hydrology.Waterfall> waterfallsForChunk(
                int chunkX, int chunkZ) {
            return waterfalls();
        }

        default java.util.List<com.github.xandergos.terraindiffusionmc.worldgen.hydrology.Waterfall> waterfalls() {
            return java.util.List.of();
        }

        default com.github.xandergos.terraindiffusionmc.worldgen.hydrology.Waterfall waterfallAt(int x, int z) {
            return null;
        }

        int carvedHeight(int var1, int var2, int var3);

        int waterY(int var1, int var2);

        default short currentAt(int x, int y, int z) {
            return 0;
        }

        /** Solid bed coordinate; prepared areas explicitly distinguish it from first-air height. */
        default int bedY(int x, int z, int rawY) {
            return carvedHeight(x, z, rawY);
        }

        default boolean isReady(int worldX, int worldZ) {
            return false;
        }
    }
}

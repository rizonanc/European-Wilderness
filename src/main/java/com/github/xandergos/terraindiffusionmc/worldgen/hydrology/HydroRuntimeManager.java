package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class HydroRuntimeManager {
    public static final int MAX_ACTIVE_EXTENTS = 8;
    private final WorldSession ticket;
    private final TerrainRepository repository;
    private final WatershedStore sidecars;
    private final Path sidecarDirectory;
    private final int tileSize;
    private final int surfaceCapY;
    private volatile Map<HydroRuntimeManager.Extent, WorldSession.RiverOverlay> active = Map.of();
    private final WorldSession.RiverOverlay composite = new WorldSession.RiverOverlay() {
        @Override
        public boolean isReady(int worldX, int worldZ) {
            if (!HydroRuntimeManager.this.ticket.isActive()) {
                return false;
            }

            WorldSession.RiverOverlay overlay = HydroRuntimeManager.this.overlayAt(worldX, worldZ);
            return overlay != null && overlay.isReady(worldX, worldZ);
        }

        @Override
        public int carvedHeight(int worldX, int worldZ, int rawY) {
            if (!HydroRuntimeManager.this.ticket.isActive()) {
                return rawY;
            }

            WorldSession.RiverOverlay overlay = HydroRuntimeManager.this.overlayAt(worldX, worldZ);
            return overlay == null ? rawY : overlay.carvedHeight(worldX, worldZ, rawY);
        }

        @Override
        public int waterY(int worldX, int worldZ) {
            if (!HydroRuntimeManager.this.ticket.isActive()) {
                return Integer.MIN_VALUE;
            }

            WorldSession.RiverOverlay overlay = HydroRuntimeManager.this.overlayAt(worldX, worldZ);
            return overlay == null ? Integer.MIN_VALUE : overlay.waterY(worldX, worldZ);
        }
    };

    public HydroRuntimeManager(
            WorldSession ticket, TerrainRepository repository, Path sidecarDirectory, int surfaceCapY) {
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sidecarDirectory = Objects.requireNonNull(sidecarDirectory, "sidecarDirectory")
                .toAbsolutePath()
                .normalize();
        this.sidecars = new WatershedStore(this.sidecarDirectory);
        this.tileSize = repository.tileSize();
        if (this.tileSize % 2 != 0 || this.tileSize > 256) {
            throw new IllegalArgumentException("G2 prototype requires an even tile size <= 256: " + this.tileSize);
        }

        if (surfaceCapY <= 63) {
            throw new IllegalArgumentException("surfaceCapY must exceed sea level");
        }

        this.surfaceCapY = surfaceCapY;
    }

    public WorldSession.RiverOverlay overlay() {
        return this.composite;
    }

    synchronized boolean prepareExtent(int anyTileX, int anyTileZ) throws IOException {
        WorldSession.requireActive(this.ticket);
        HydroRuntimeManager.Extent extent = parentOfTile(anyTileX, anyTileZ);
        if (this.active.containsKey(extent)) {
            return true;
        }

        this.refuseIndependentNeighbor(extent);
        this.ensureCapacity();
        WatershedStore.WatershedKey parentKey = this.parentKey(extent);
        Optional<WatershedPlan> saved = this.sidecars.load(parentKey);
        TerrainTile[][] raw = this.peekRawExtent(extent);
        if (raw == null) {
            if (saved.isPresent()) {
                throw new IllegalStateException("Frozen watershed has missing raw terrain: " + extent);
            } else {
                return false;
            }
        } else {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    Optional<WatershedStore.Decision> frozen = this.sidecars.readDecision(
                            this.childKey(extent, dx, dz),
                            this.ticket.profile().worldScale(),
                            this.ticket.profile().generatorVersion());
                    if (frozen.orElse(null) == WatershedStore.Decision.DISABLED) {
                        if (saved.isPresent()) {
                            throw new IllegalStateException("Frozen watershed has a disabled tile: "
                                    + extent
                                    + " child ("
                                    + dx
                                    + ","
                                    + dz
                                    + ")");
                        }

                        return false;
                    }

                    if (saved.isEmpty() && frozen.isPresent()) {
                        throw new IllegalStateException(
                                "Hydro tile decision exists without its parent watershed: " + extent);
                    }

                    if (saved.isPresent() && frozen.isEmpty()) {
                        throw new IllegalStateException("Frozen watershed is missing a tile decision: "
                                + extent
                                + " child ("
                                + dx
                                + ","
                                + dz
                                + ")");
                    }
                }
            }

            WatershedPlan plan = saved.orElseGet(() -> this.planFromRaw(extent, raw));
            this.verifyPlanExtent(plan, extent);
            SeamContract seam = SeamContract.of(plan, this.tileSize);
            WorldSession.requireActive(this.ticket);
            if (saved.isEmpty()) {
                this.sidecars.saveAtomic(plan, parentKey);
            }

            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    WorldSession.requireActive(this.ticket);
                    WatershedStore.Decision decision = this.sidecars.freezeTileDecision(
                            this.childKey(extent, dx, dz),
                            this.ticket.profile().worldScale(),
                            this.ticket.profile().generatorVersion(),
                            true);
                    if (decision != WatershedStore.Decision.ACTIVE) {
                        return false;
                    }
                }
            }

            this.register(extent, plan, seam);
            return true;
        }
    }

    public synchronized boolean loadExtent(int anyTileX, int anyTileZ) throws IOException {
        WorldSession.requireActive(this.ticket);
        HydroRuntimeManager.Extent extent = parentOfTile(anyTileX, anyTileZ);
        if (this.active.containsKey(extent)) {
            return true;
        }

        this.refuseIndependentNeighbor(extent);
        this.ensureCapacity();
        Optional<WatershedPlan> saved = this.sidecars.load(this.parentKey(extent));
        if (saved.isEmpty()) {
            return false;
        }

        WatershedPlan plan = saved.get();
        this.verifyPlanExtent(plan, extent);
        SeamContract seam = SeamContract.of(plan, this.tileSize);

        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                Optional<WatershedStore.Decision> decision = this.sidecars.readDecision(
                        this.childKey(extent, dx, dz),
                        this.ticket.profile().worldScale(),
                        this.ticket.profile().generatorVersion());
                if (decision.isEmpty()) {
                    throw new IllegalStateException("Frozen watershed is missing a tile decision: "
                            + extent
                            + " child ("
                            + dx
                            + ","
                            + dz
                            + ")");
                }

                if (decision.get() != WatershedStore.Decision.ACTIVE) {
                    throw new IllegalStateException(
                            "Frozen watershed has a disabled tile: " + extent + " child (" + dx + "," + dz + ")");
                }
            }
        }

        if (this.peekRawExtent(extent) == null) {
            throw new IllegalStateException("Frozen ACTIVE watershed has missing raw terrain: " + extent);
        }

        this.register(extent, plan, seam);
        return true;
    }

    public synchronized int loadSavedExtents() throws IOException {
        WorldSession.requireActive(this.ticket);
        if (!Files.isDirectory(this.sidecarDirectory)) {
            return 0;
        }

        String prefix = "ws_" + this.ticket.worldSeed() + "_" + this.tileSize + "_";

        List<Path> paths;
        try (Stream<Path> files = Files.list(this.sidecarDirectory)) {
            paths = files.filter(pathx -> {
                        String name = pathx.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".wss");
                    })
                    .sorted(Comparator.comparing(pathx -> pathx.getFileName().toString()))
                    .toList();
        }

        if (paths.size() > 8) {
            throw new IllegalStateException("Too many frozen watershed extents for G2 prototype: " + paths.size());
        }

        int loaded = 0;

        for (Path path : paths) {
            HydroRuntimeManager.Extent extent = parseExtentPath(path, prefix);
            if (this.loadExtent(extent.tileX(), extent.tileZ())) {
                loaded++;
            }
        }

        return loaded;
    }

    private void register(HydroRuntimeManager.Extent extent, WatershedPlan plan, SeamContract seam) {
        WorldSession.requireActive(this.ticket);
        WorldSession.RiverOverlay river = new HydroPrepareService(
                        plan,
                        seam,
                        (tileX, tileZ) -> tileX.intValue() >= extent.tileX()
                                && tileX.intValue() < extent.tileX() + 2L
                                && tileZ.intValue() >= extent.tileZ()
                                && tileZ.intValue() < extent.tileZ() + 2L)
                .asOverlay();
        Map<HydroRuntimeManager.Extent, WorldSession.RiverOverlay> next = new HashMap<>(this.active);
        next.put(extent, river);
        this.active = Map.copyOf(next);
    }

    private WorldSession.RiverOverlay overlayAt(int worldX, int worldZ) {
        return this.active.get(
                parentOfTile(Math.floorDiv(worldX, this.tileSize), Math.floorDiv(worldZ, this.tileSize)));
    }

    private void ensureCapacity() {
        if (this.active.size() >= 8) {
            throw new IllegalStateException("G2 prototype active extent limit reached: 8");
        }
    }

    private void refuseIndependentNeighbor(HydroRuntimeManager.Extent extent) {
        for (int dz = -2; dz <= 2; dz += 2) {
            for (int dx = -2; dx <= 2; dx += 2) {
                if (dx != 0 || dz != 0) {
                    long neighborX = (long) extent.tileX() + dx;
                    long neighborZ = (long) extent.tileZ() + dz;
                    if (neighborX >= -2147483648L
                            && neighborX <= 2147483647L
                            && neighborZ >= -2147483648L
                            && neighborZ <= 2147483647L) {
                        HydroRuntimeManager.Extent neighbor =
                                new HydroRuntimeManager.Extent((int) neighborX, (int) neighborZ);
                        if (this.active.containsKey(neighbor)
                                || Files.exists(this.sidecars.sidecarPath(this.parentKey(neighbor)))) {
                            throw new IllegalStateException(
                                    "Independent watershed parents have no shared border contract: "
                                            + extent
                                            + " touches "
                                            + neighbor);
                        }
                    }
                }
            }
        }
    }

    private TerrainTile[][] peekRawExtent(HydroRuntimeManager.Extent extent) {
        TerrainTile[][] raw = new TerrainTile[2][2];

        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                Optional<TerrainTile> ready = this.repository.peek(this.childKey(extent, dx, dz));
                if (ready.isEmpty()) {
                    return null;
                }

                raw[dz][dx] = ready.get();
            }
        }

        return raw;
    }

    private WatershedPlan planFromRaw(HydroRuntimeManager.Extent extent, TerrainTile[][] raw) {
        int originX = Math.multiplyExact(extent.tileX(), this.tileSize);
        int originZ = Math.multiplyExact(extent.tileZ(), this.tileSize);
        int[][] coarse = new int[this.tileSize][this.tileSize];

        for (int cz = 0; cz < this.tileSize; cz++) {
            int worldZ = Math.addExact(originZ, Math.addExact(cz * 2, 1));
            int dz = Math.floorDiv(worldZ, this.tileSize) - extent.tileZ();
            int localZ = Math.floorMod(worldZ, this.tileSize);

            for (int cx = 0; cx < this.tileSize; cx++) {
                int worldX = Math.addExact(originX, Math.addExact(cx * 2, 1));
                int dx = Math.floorDiv(worldX, this.tileSize) - extent.tileX();
                int localX = Math.floorMod(worldX, this.tileSize);
                short metres = raw[dz][dx].heightAt(localX, localZ);
                coarse[cz][cx] = MountainHeightField.sample(
                                this.ticket.worldSeed(),
                                worldX,
                                worldZ,
                                metres,
                                this.ticket.profile().worldScale(),
                                this.surfaceCapY,
                                this.ticket.profile().mountainRelief())
                        .surfaceY();
            }
        }

        return HydroPrepareService.prepareExtent(
                this.ticket.worldSeed(), originX, originZ, this.tileSize, this.tileSize, coarse);
    }

    private void verifyPlanExtent(WatershedPlan plan, HydroRuntimeManager.Extent extent) {
        if (plan.seed() != this.ticket.worldSeed()
                || plan.generatorVersion() != this.ticket.profile().generatorVersion()
                || plan.coarseOriginX() != Math.multiplyExact(extent.tileX(), this.tileSize)
                || plan.coarseOriginZ() != Math.multiplyExact(extent.tileZ(), this.tileSize)
                || plan.coarseWidth() != this.tileSize
                || plan.coarseHeight() != this.tileSize) {
            throw new IllegalStateException("Foreign watershed geometry for parent extent " + extent);
        }
    }

    private WatershedStore.WatershedKey parentKey(HydroRuntimeManager.Extent extent) {
        return new WatershedStore.WatershedKey(
                this.ticket.worldSeed(),
                this.ticket.fingerprint(),
                extent.tileX(),
                extent.tileZ(),
                this.tileSize,
                this.ticket.profile().worldScale(),
                this.ticket.profile().generatorVersion());
    }

    private TerrainTileKey childKey(HydroRuntimeManager.Extent extent, int dx, int dz) {
        return TerrainTileKey.ofTile(
                this.ticket.worldSeed(),
                Math.addExact(extent.tileX(), dx),
                Math.addExact(extent.tileZ(), dz),
                this.tileSize,
                this.ticket.fingerprint());
    }

    private static HydroRuntimeManager.Extent parentOfTile(int tileX, int tileZ) {
        return new HydroRuntimeManager.Extent(
                Math.multiplyExact(Math.floorDiv(tileX, 2), 2), Math.multiplyExact(Math.floorDiv(tileZ, 2), 2));
    }

    private static HydroRuntimeManager.Extent parseExtentPath(Path path, String prefix) {
        String name = path.getFileName().toString();
        String coords = name.substring(prefix.length(), name.length() - ".wss".length());
        String[] parts = coords.split("_", -1);
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid watershed sidecar name: " + name);
        }

        try {
            int tileX = Integer.parseInt(parts[0]);
            int tileZ = Integer.parseInt(parts[1]);
            HydroRuntimeManager.Extent extent = parentOfTile(tileX, tileZ);
            if (extent.tileX() == tileX && extent.tileZ() == tileZ) {
                return extent;
            } else {
                throw new IllegalStateException("Unaligned watershed parent sidecar: " + name);
            }
        } catch (NumberFormatException badName) {
            throw new IllegalStateException("Invalid watershed sidecar name: " + name, badName);
        }
    }

    private record Extent(int tileX, int tileZ) {}
}

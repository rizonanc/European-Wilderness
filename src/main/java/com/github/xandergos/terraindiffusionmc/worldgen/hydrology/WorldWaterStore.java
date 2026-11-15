package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage.*;

/** Frozen world plans. Version, seed, terrain identity and layout are all checked. */
final class WorldWaterStore {
    private static final int MAGIC = 0x45575731, VERSION = 4;
    private final Path root;
    private final long seed;
    private final String fingerprint;
    private final int tileSize;
    private final WorldWaterGrid grid;

    WorldWaterStore(Path root, long seed, String fingerprint, int tileSize, WorldWaterGrid grid) {
        this.root = root;
        this.seed = seed;
        this.fingerprint = fingerprint;
        this.tileSize = tileSize;
        this.grid = grid;
    }

    private Path path(String kind, WorldWaterGrid.Cell c) {
        return root.resolve(kind).resolve(c.x() + "_" + c.z() + ".eww");
    }

    boolean hasUnit(WorldWaterGrid.Cell cell) {
        return Files.isRegularFile(path("units", cell));
    }

    boolean hasTile(WorldWaterGrid.Cell cell) {
        return Files.isRegularFile(path("tiles", cell));
    }

    WorldWaterUnit loadUnit(WorldWaterGrid.Cell cell) throws IOException {
        long trace = PerformanceDiagnostics.begin(WATER_IO_READ);
        try {
            Path p = path("units", cell);
            if (!Files.exists(p)) return null;
            try (var in = input(p, 1, cell)) {
                int count = in.readInt();
                if (count < 0 || count > grid.unitSide() * grid.unitSide())
                    throw new IOException("Invalid water unit size");
                int revision = in.version >= 2 ? in.readInt() : 1;
                int[] indices = new int[count];
                short[] levels = new short[count];
                byte[] lakes = new byte[count];
                for (int i = 0; i < count; i++) {
                    indices[i] = in.readInt();
                    levels[i] = in.readShort();
                    lakes[i] = in.readByte();
                }
                var falls = readFalls(in);
                short[] currents = in.version >= 3 ? RiverCurrent.read(in, count) : null;
                int depthVariation = in.version >= 4 ? in.readUnsignedByte() : 100;
                requireEnd(in);
                return new WorldWaterUnit(
                        grid, cell, indices, levels, lakes, falls, revision, currents, depthVariation);
            } catch (IOException | IllegalArgumentException e) {
                failed(Code.HYD_DATA, "WATER_UNIT_READ", e);
                throw new IOException("Invalid frozen water unit " + cell, e);
            }
        } finally {
            PerformanceDiagnostics.end(trace);
        }
    }

    WorldWaterTile loadTile(WorldWaterGrid.Cell cell) throws IOException {
        long trace = PerformanceDiagnostics.begin(WATER_IO_READ);
        try {
            Path p = path("tiles", cell);
            if (!Files.exists(p)) return null;
            try (var in = input(p, 2, cell)) {
                int revision = in.version >= 2 ? in.readInt() : 1;
                int count = grid.core() * grid.core();
                int[] air = new int[count], water = new int[count];
                for (int i = 0; i < count; i++) air[i] = in.readShort();
                for (int i = 0; i < count; i++) {
                    int w = in.readShort();
                    water[i] = w == Short.MIN_VALUE ? PreparedWaterArea.NONE : w;
                }
                var falls = readFalls(in);
                short[] currents = in.version >= 3 ? RiverCurrent.read(in, count) : null;
                requireEnd(in);
                return new WorldWaterTile(grid, cell, air, water, falls, revision, currents);
            } catch (IOException | IllegalArgumentException e) {
                failed(Code.HYD_DATA, "WATER_TILE_READ", e);
                throw new IOException("Invalid frozen water tile " + cell, e);
            }
        } finally {
            PerformanceDiagnostics.end(trace);
        }
    }

    private static final class PlanInput extends DataInputStream {
        int version;

        PlanInput(InputStream in) {
            super(in);
        }
    }

    private PlanInput input(Path path, int type, WorldWaterGrid.Cell cell) throws IOException {
        var in = new PlanInput(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))));
        try {
            if (in.readInt() != MAGIC) throw new IOException("Invalid world water magic");
            in.version = in.readInt();
            if (in.version < 1
                    || in.version > VERSION
                    || in.readInt() != type
                    || in.readLong() != seed
                    || !in.readUTF().equals(fingerprint)
                    || in.readInt() != tileSize
                    || in.readInt() != grid.core()
                    || in.readInt() != grid.unitHalo()
                    || in.readInt() != grid.renderHalo()
                    || in.readInt() != cell.x()
                    || in.readInt() != cell.z())
                throw new IOException("Foreign or incompatible world water plan: " + path);
            return in;
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    void save(WorldWaterUnit unit) throws IOException {
        int version = unit.depthVariation == 100 ? 3 : VERSION;
        write(
                path("units", unit.cell),
                1,
                unit.cell,
                version,
                out -> {
                    out.writeInt(unit.indices.length);
                    out.writeInt(unit.revision);
                    for (int i = 0; i < unit.indices.length; i++) {
                        out.writeInt(unit.indices[i]);
                        out.writeShort(unit.levels[i]);
                        out.writeByte(unit.lakes[i]);
                    }
                    writeFalls(out, unit.falls);
                    RiverCurrent.write(out, unit.currents);
                    if (version >= 4) out.writeByte(unit.depthVariation);
                });
    }

    void save(WorldWaterTile tile) throws IOException {
        write(
                path("tiles", tile.cell),
                2,
                tile.cell,
                3,
                out -> {
                    out.writeInt(tile.revision);
                    for (int a : tile.air) out.writeShort(a);
                    for (int w : tile.water) out.writeShort(w == PreparedWaterArea.NONE ? Short.MIN_VALUE : w);
                    writeFalls(out, tile.falls);
                    RiverCurrent.write(out, tile.currents);
                });
    }

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private synchronized void write(Path target, int type, WorldWaterGrid.Cell cell, int version, Body body)
            throws IOException {
        long trace = PerformanceDiagnostics.begin(WATER_IO_WRITE);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) throw new IOException("Frozen water plan already exists: " + target);
            Path temp = Files.createTempFile(target.getParent(), "water-", ".tmp");
            try {
                try (var out =
                        new DataOutputStream(
                                new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                    out.writeInt(MAGIC);
                    out.writeInt(version);
                    out.writeInt(type);
                    out.writeLong(seed);
                    out.writeUTF(fingerprint);
                    out.writeInt(tileSize);
                    out.writeInt(grid.core());
                    out.writeInt(grid.unitHalo());
                    out.writeInt(grid.renderHalo());
                    out.writeInt(cell.x());
                    out.writeInt(cell.z());
                    body.write(out);
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | RuntimeException failure) {
            failed(Code.HYD_WRITE, "WATER_WRITE", failure);
            throw failure;
        } finally {
            PerformanceDiagnostics.end(trace);
        }
    }

    private static void writeFalls(DataOutputStream out, List<Waterfall> falls) throws IOException {
        out.writeInt(falls.size());
        for (var f : falls) {
            out.writeInt(f.x());
            out.writeInt(f.z());
            out.writeInt(f.dx());
            out.writeInt(f.dz());
            out.writeInt(f.upperY());
            out.writeInt(f.lowerY());
            out.writeInt(f.width());
            out.writeInt(f.run());
        }
    }

    private static List<Waterfall> readFalls(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 1) throw new IOException("Invalid world waterfall count");
        List<Waterfall> falls = new ArrayList<>();
        for (int i = 0; i < count; i++)
            falls.add(
                    new Waterfall(
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.readInt()));
        return List.copyOf(falls);
    }

    private static void requireEnd(DataInputStream in) throws IOException {
        if (in.read() != -1) throw new IOException("Trailing world water data");
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

public final class WatershedStore {
   public static final int SIDECAR_FORMAT = 1;
   private static final int PLAN_MAGIC = 1465075761;
   private static final int DECISION_MAGIC = 1465074737;
   private static final int UNIT_MAGIC = 1465079089;
   private static final int JOURNAL_MAGIC = 1465076273;
   private static final int MAX_FINGERPRINT_BYTES = 512;
   private static final int MAX_BODY_BYTES = 67108864;
   private static final int LOCK_STRIPES = 64;
   private static final Object[] PLAN_LOCKS = new Object[64];
   static volatile WatershedStore.UnitSidecarFault failAfterUnitSidecarForTest;
   private final Path directory;

   public WatershedStore(Path directory) {
      this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
   }

   public Path sidecarPath(WatershedStore.WatershedKey key) {
      Objects.requireNonNull(key, "key");
      return this.directory.resolve("ws_" + key.seed() + "_" + key.tileSize() + "_" + key.tileX() + "_" + key.tileZ() + ".wss");
   }

   public Path unitSidecarPath(WatershedStore.UnitKey key) {
      Objects.requireNonNull(key, "key");
      return this.directory
         .resolve(
            "wsu_" + key.seed() + "_" + key.tileSizeBlocks() + "_" + key.originX() + "_" + key.originZ() + "_" + key.width() + "x" + key.height() + ".wss"
         );
   }

   public Path unitJournalPath(WatershedStore.UnitKey key) {
      Objects.requireNonNull(key, "key");
      return this.unitSidecarPath(key).resolveSibling(this.unitSidecarPath(key).getFileName().toString() + ".journal");
   }

   public Path decisionPathForTest(TerrainTileKey key) {
      Objects.requireNonNull(key, "key");
      return this.decisionPath(key);
   }

   public boolean hasUnitJournal(WatershedStore.UnitKey key) throws IOException {
      Objects.requireNonNull(key, "key");
      synchronized (this.unitLock(key)) {
         return Files.exists(this.unitJournalPath(key));
      }
   }

   public WatershedPlan publishUnit(
      long seed,
      int unitOriginX,
      int unitOriginZ,
      int parentCoarseW,
      int parentCoarseH,
      Map<HydroPrepareService.ParentCoord, int[][]> parentBases,
      WatershedStore.UnitKey key,
      List<TerrainTileKey> daughterTiles,
      Set<Long> contractedOutletIds
   ) throws IOException {
      WatershedPlan unit = HydroPrepareService.prepareUnit(seed, unitOriginX, unitOriginZ, parentCoarseW, parentCoarseH, parentBases);
      this.saveUnitAtomic(unit, key, daughterTiles, contractedOutletIds);
      return unit;
   }

   public void saveUnitAtomic(WatershedPlan unit, WatershedStore.UnitKey key, List<TerrainTileKey> daughterTiles, Set<Long> contractedOutletIds) throws IOException {
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(daughterTiles, "daughterTiles");
      Objects.requireNonNull(contractedOutletIds, "contractedOutletIds");
      if (unit.seed() != key.seed()) {
         throw new IllegalArgumentException("unit seed " + unit.seed() + " does not match key seed " + key.seed());
      }

      if (unit.generatorVersion() != key.generatorVersion()) {
         throw new IllegalArgumentException("unit version " + unit.generatorVersion() + " does not match key version " + key.generatorVersion());
      }

      if (unit.coarseOriginX() != key.originX()
         || unit.coarseOriginZ() != key.originZ()
         || unit.coarseWidth() != key.width()
         || unit.coarseHeight() != key.height()) {
         throw new IllegalArgumentException("unit extent does not match key " + key);
      }

      if (daughterTiles.isEmpty()) {
         throw new IllegalArgumentException("a unit needs at least one daughter tile");
      }

      for (TerrainTileKey tile : daughterTiles) {
         Objects.requireNonNull(tile, "daughter tile");
         if (tile.seed() != key.seed() || tile.tileSize() != key.tileSizeBlocks()) {
            throw new IllegalArgumentException("daughter tile " + tile + " does not belong to unit " + key);
         }
      }

      SeamContract seam = SeamContract.of(unit, key.tileSizeBlocks());
      new CrossTileRiverTracer(unit, seam).verifyCompleteDrainage(contractedOutletIds);

      for (TerrainTileKey tile : daughterTiles) {
         Optional<WatershedStore.Decision> frozen = this.readDecision(tile, key.scale(), key.generatorVersion());
         if (frozen.orElse(null) == WatershedStore.Decision.DISABLED) {
            throw new IllegalStateException("Unit tile is frozen DISABLED for this world: " + tile);
         }
      }

      byte[] fingerprint = key.fingerprint().getBytes(StandardCharsets.UTF_8);
      if (fingerprint.length > 512) {
         throw new IllegalArgumentException("fingerprint too long for watershed unit sidecar");
      }

      byte[] data = unitPayload(unit, key, fingerprint);
      CRC32 crc = new CRC32();
      crc.update(data);
      ByteArrayOutputStream encoded = new ByteArrayOutputStream(data.length + 4);
      DataOutputStream out = new DataOutputStream(encoded);

      try {
         out.write(data);
         out.writeInt((int)crc.getValue());
      } catch (Throwable var33) {
         try {
            out.close();
         } catch (Throwable var31) {
            var33.addSuppressed(var31);
         }

         throw var33;
      }

      out.close();
      byte[] var43 = encoded.toByteArray();
      CRC32 sidecarCrc = new CRC32();
      sidecarCrc.update(var43);
      int sidecarCrcValue = (int)sidecarCrc.getValue();
      Files.createDirectories(this.directory);
      Path target = this.unitSidecarPath(key);
      Path journal = this.unitJournalPath(key);
      synchronized (this.unitLock(key)) {
         if (Files.exists(journal)) {
            WatershedStore.Journal existing = this.readJournal(key);
            if (!journalMatches(existing, key, daughterTiles, contractedOutletIds, sidecarCrcValue)) {
               throw new IllegalStateException("Conflicting watershed publication in progress: " + journal);
            }
         } else {
            this.writeJournal(key, daughterTiles, contractedOutletIds, sidecarCrcValue);
         }

         try {
            if (Files.exists(target)) {
               byte[] existing;
               try (InputStream in = Files.newInputStream(target)) {
                  existing = in.readNBytes(67108865);
               }

               if (!Arrays.equals(existing, var43)) {
                  throw new IllegalStateException("Conflicting or corrupt frozen watershed unit: " + target);
               }
            } else {
               Path temp = Files.createTempFile(this.directory, ".watershed-unit-", ".tmp");
               boolean moved = false;

               try {
                  Files.write(temp, var43);

                  try {
                     Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                  } catch (AtomicMoveNotSupportedException unsupported) {
                     Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                  }

                  moved = true;
               } finally {
                  if (!moved) {
                     Files.deleteIfExists(temp);
                  }
               }
            }

            WatershedStore.UnitSidecarFault fault = failAfterUnitSidecarForTest;
            if (fault != null) {
               fault.run();
            }

            for (TerrainTileKey tile : daughterTiles) {
               WatershedStore.Decision decision = this.freezeTileDecision(tile, key.scale(), key.generatorVersion(), true);
               if (decision != WatershedStore.Decision.ACTIVE) {
                  throw new IllegalStateException("Unit tile decision is not ACTIVE after publication: " + tile);
               }
            }

            Files.deleteIfExists(journal);
         } catch (IOException | RuntimeException failed) {
            throw failed;
         }
      }
   }

   public Optional<WatershedPlan> loadUnit(WatershedStore.UnitKey key) throws IOException {
      Objects.requireNonNull(key, "key");
      synchronized (this.unitLock(key)) {
         if (Files.exists(this.unitJournalPath(key))) {
            throw new IllegalStateException("Incomplete watershed publication, resume byte-identical: " + key);
         }

         Path path = this.unitSidecarPath(key);

         byte[] bytes;
         try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes(67108865);
         } catch (IOException missing) {
            if (!Files.exists(path)) {
               return Optional.empty();
            }

            throw missing;
         }

         if (bytes.length > 67108864) {
            throw new IllegalStateException("Watershed unit sidecar too large: " + path);
         } else {
            return Optional.of(decodeUnit(path, bytes, key));
         }
      }
   }

   public void requireUnitDecisions(WatershedStore.UnitKey key, List<TerrainTileKey> daughterTiles) throws IOException {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(daughterTiles, "daughterTiles");
      synchronized (this.unitLock(key)) {
         if (Files.exists(this.unitJournalPath(key))) {
            throw new IllegalStateException("Incomplete watershed publication, resume byte-identical: " + key);
         }

         for (TerrainTileKey tile : daughterTiles) {
            Optional<WatershedStore.Decision> decision = this.readDecision(tile, key.scale(), key.generatorVersion());
            if (decision.isEmpty()) {
               throw new IllegalStateException("Frozen watershed unit is missing a tile decision: " + tile);
            }

            if (decision.get() != WatershedStore.Decision.ACTIVE) {
               throw new IllegalStateException("Frozen watershed unit has a disabled tile: " + tile);
            }
         }
      }
   }

   public void saveAtomic(WatershedPlan plan, WatershedStore.WatershedKey key) throws IOException {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(key, "key");
      if (plan.generatorVersion() != key.generatorVersion()) {
         throw new IllegalArgumentException("plan version " + plan.generatorVersion() + " does not match key version " + key.generatorVersion());
      }

      byte[] fingerprint = key.fingerprint().getBytes(StandardCharsets.UTF_8);
      if (fingerprint.length > 512) {
         throw new IllegalArgumentException("fingerprint too long for watershed sidecar");
      }

      byte[] data = planPayload(plan, key, fingerprint);
      CRC32 crc = new CRC32();
      crc.update(data);
      ByteArrayOutputStream encoded = new ByteArrayOutputStream(data.length + 4);
      DataOutputStream out = new DataOutputStream(encoded);

      try {
         out.write(data);
         out.writeInt((int)crc.getValue());
      } catch (Throwable var26) {
         try {
            out.close();
         } catch (Throwable var24) {
            var26.addSuppressed(var24);
         }

         throw var26;
      }

      out.close();
      byte[] var30 = encoded.toByteArray();
      Files.createDirectories(this.directory);
      Path target = this.sidecarPath(key);
      synchronized (PLAN_LOCKS[Math.floorMod(target.hashCode(), PLAN_LOCKS.length)]) {
         if (Files.exists(target)) {
            byte[] existing;
            try (InputStream in = Files.newInputStream(target)) {
               existing = in.readNBytes(67108865);
            }

            if (!Arrays.equals(existing, var30)) {
               throw new IllegalStateException("Conflicting or corrupt frozen watershed sidecar: " + target);
            }
         } else {
            Path temp = Files.createTempFile(this.directory, ".watershed-", ".tmp");
            boolean moved = false;

            try {
               Files.write(temp, var30);

               try {
                  Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
               } catch (AtomicMoveNotSupportedException unsupported) {
                  Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
               }

               moved = true;
            } finally {
               if (!moved) {
                  Files.deleteIfExists(temp);
               }
            }
         }
      }
   }

   public Optional<WatershedPlan> load(WatershedStore.WatershedKey key) throws IOException {
      Objects.requireNonNull(key, "key");
      Path path = this.sidecarPath(key);

      byte[] bytes;
      try (InputStream in = Files.newInputStream(path)) {
         bytes = in.readNBytes(67108865);
      } catch (IOException missing) {
         if (!Files.exists(path)) {
            return Optional.empty();
         }

         throw missing;
      }

      if (bytes.length > 67108864) {
         throw new IllegalStateException("Watershed sidecar too large: " + path);
      } else {
         return Optional.of(decode(path, bytes, key));
      }
   }

   public WatershedStore.Decision freezeTileDecision(TerrainTileKey key, int worldScale, int generatorVersion, boolean tileReady) throws IOException {
      Objects.requireNonNull(key, "key");
      if (worldScale > 0 && generatorVersion > 0) {
         byte[] fingerprint = key.fingerprint().getBytes(StandardCharsets.UTF_8);
         if (fingerprint.length > 512) {
            throw new IllegalArgumentException("fingerprint too long for hydro decision marker");
         }

         Path path = this.decisionPath(key);
         synchronized (PLAN_LOCKS[Math.floorMod(path.hashCode(), PLAN_LOCKS.length)]) {
            if (Files.exists(path)) {
               WatershedStore.Decision existing = readDecision(path, key, worldScale, generatorVersion);
               if (existing == WatershedStore.Decision.ACTIVE && !tileReady) {
                  throw new IllegalStateException("Active hydro decision requires its prepared terrain tile: " + key);
               } else {
                  return existing;
               }
            } else {
               WatershedStore.Decision decision = tileReady ? WatershedStore.Decision.ACTIVE : WatershedStore.Decision.DISABLED;
               this.writeDecision(path, key, worldScale, generatorVersion, decision, fingerprint);
               return decision;
            }
         }
      } else {
         throw new IllegalArgumentException("worldScale and generatorVersion must be positive");
      }
   }

   public Optional<WatershedStore.Decision> readDecision(TerrainTileKey key, int worldScale, int generatorVersion) throws IOException {
      Objects.requireNonNull(key, "key");
      Path path = this.decisionPath(key);
      return !Files.exists(path) ? Optional.empty() : Optional.of(readDecision(path, key, worldScale, generatorVersion));
   }

   private Path decisionPath(TerrainTileKey key) {
      return this.directory.resolve("wsd_" + key.seed() + "_" + key.tileSize() + "_" + key.tileX() + "_" + key.tileZ() + ".wsd");
   }

   private Object unitLock(WatershedStore.UnitKey key) {
      return PLAN_LOCKS[Math.floorMod(this.unitSidecarPath(key).hashCode(), PLAN_LOCKS.length)];
   }

   private static boolean journalMatches(
      WatershedStore.Journal existing, WatershedStore.UnitKey key, List<TerrainTileKey> daughters, Set<Long> contracted, int sidecarCrc
   ) {
      if (existing.sidecarCrc() != sidecarCrc) {
         return false;
      }

      List<long[]> wantDaughters = canonicalDaughters(daughters);
      if (wantDaughters.size() != existing.daughters().size()) {
         return false;
      }

      for (int i = 0; i < wantDaughters.size(); i++) {
         if (wantDaughters.get(i)[0] != existing.daughters().get(i)[0] || wantDaughters.get(i)[1] != existing.daughters().get(i)[1]) {
            return false;
         }
      }

      List<Long> wantContracted = contracted.stream().sorted().toList();
      return wantContracted.equals(existing.contracted());
   }

   private static List<long[]> canonicalDaughters(List<TerrainTileKey> daughters) {
      List<long[]> codes = new ArrayList<>(daughters.size());

      for (TerrainTileKey tile : daughters) {
         codes.add(new long[]{tile.tileX(), tile.tileZ()});
      }

      codes.sort((a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
      return codes;
   }

   private void writeJournal(WatershedStore.UnitKey key, List<TerrainTileKey> daughters, Set<Long> contracted, int sidecarCrc) throws IOException {
      byte[] fingerprint = key.fingerprint().getBytes(StandardCharsets.UTF_8);
      List<long[]> tiles = canonicalDaughters(daughters);
      List<Long> outletIds = contracted.stream().sorted().toList();

      byte[] data;
      try (
         ByteArrayOutputStream bytes = new ByteArrayOutputStream(128 + fingerprint.length + tiles.size() * 8 + outletIds.size() * 8);
         DataOutputStream out = new DataOutputStream(bytes);
      ) {
         out.writeInt(1465076273);
         out.writeByte(1);
         out.writeLong(key.seed());
         out.writeInt(key.originX());
         out.writeInt(key.originZ());
         out.writeInt(key.width());
         out.writeInt(key.height());
         out.writeInt(key.tileSizeBlocks());
         out.writeInt(key.scale());
         out.writeInt(key.generatorVersion());
         out.writeShort(fingerprint.length);
         out.write(fingerprint);
         out.writeInt(sidecarCrc);
         out.writeInt(tiles.size());

         for (long[] tile : tiles) {
            out.writeInt((int)tile[0]);
            out.writeInt((int)tile[1]);
         }

         out.writeInt(outletIds.size());

         for (long id : outletIds) {
            out.writeLong(id);
         }

         out.flush();
         data = bytes.toByteArray();
      }

      CRC32 crc = new CRC32();
      crc.update(data);
      Path var34 = this.unitJournalPath(key);
      Path var36 = Files.createTempFile(this.directory, ".watershed-journal-", ".tmp");
      boolean var38 = false;

      try {
         try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(var36))) {
            out.write(data);
            out.writeInt((int)crc.getValue());
         }

         try {
            Files.move(var36, var34, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(var36, var34, StandardCopyOption.REPLACE_EXISTING);
         }

         var38 = true;
      } finally {
         if (!var38) {
            Files.deleteIfExists(var36);
         }
      }
   }

   private WatershedStore.Journal readJournal(WatershedStore.UnitKey key) throws IOException {
      Path journal = this.unitJournalPath(key);

      byte[] bytes;
      try (InputStream in = Files.newInputStream(journal)) {
         bytes = in.readNBytes(1048577);
      }

      if (bytes.length <= 1048576 && bytes.length >= 60) {
         int bodyLength = bytes.length - 4;
         CRC32 crc = new CRC32();
         crc.update(bytes, 0, bodyLength);

         try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1465076273 || in.readUnsignedByte() != 1) {
               throw new IllegalStateException("Unknown watershed journal: " + journal);
            }

            if (in.readLong() != key.seed()
               || in.readInt() != key.originX()
               || in.readInt() != key.originZ()
               || in.readInt() != key.width()
               || in.readInt() != key.height()
               || in.readInt() != key.tileSizeBlocks()
               || in.readInt() != key.scale()
               || in.readInt() != key.generatorVersion()) {
               throw new IllegalStateException("Foreign watershed journal: " + journal);
            }

            int fpLength = in.readUnsignedShort();
            if (fpLength > 512 || fpLength > in.available() - 16) {
               throw new IllegalStateException("Invalid watershed journal fingerprint: " + journal);
            }

            byte[] fp = in.readNBytes(fpLength);
            if (!Arrays.equals(fp, key.fingerprint().getBytes(StandardCharsets.UTF_8))) {
               throw new IllegalStateException("Foreign watershed journal fingerprint: " + journal);
            }

            int sidecarCrc = in.readInt();
            int tileCount = in.readInt();
            if (tileCount < 1 || tileCount > 1024 || tileCount > in.available() / 8) {
               throw new IllegalStateException("Invalid watershed journal tiles: " + journal);
            }

            List<long[]> tiles = new ArrayList<>(tileCount);

            for (int i = 0; i < tileCount; i++) {
               tiles.add(new long[]{in.readInt(), in.readInt()});
            }

            int outletCount = in.readInt();
            if (outletCount < 0 || outletCount > 4096 || outletCount > in.available() / 8) {
               throw new IllegalStateException("Invalid watershed journal outlets: " + journal);
            }

            List<Long> outlets = new ArrayList<>(outletCount);

            for (int i = 0; i < outletCount; i++) {
               outlets.add(in.readLong());
            }

            if (in.available() != 4 || in.readInt() != (int)crc.getValue()) {
               throw new IllegalStateException("Corrupt watershed journal: " + journal);
            } else {
               return new WatershedStore.Journal(sidecarCrc, List.copyOf(tiles), List.copyOf(outlets));
            }
         } catch (IOException malformed) {
            throw new IllegalStateException("Corrupt watershed journal: " + journal, malformed);
         }
      } else {
         throw new IllegalStateException("Invalid watershed journal size: " + journal);
      }
   }

   private static byte[] unitPayload(WatershedPlan plan, WatershedStore.UnitKey key, byte[] fingerprint) throws IOException {
      int[][] base = plan.coarseBase();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + fingerprint.length + base.length * base[0].length * 4);

      try (DataOutputStream out = new DataOutputStream(bytes)) {
         out.writeInt(1465079089);
         out.writeByte(1);
         out.writeLong(key.seed());
         out.writeInt(key.originX());
         out.writeInt(key.originZ());
         out.writeInt(key.width());
         out.writeInt(key.height());
         out.writeInt(key.tileSizeBlocks());
         out.writeInt(key.scale());
         out.writeInt(key.generatorVersion());
         out.writeShort(fingerprint.length);
         out.write(fingerprint);

         for (int[] row : base) {
            for (int value : row) {
               out.writeInt(value);
            }
         }
      }

      return bytes.toByteArray();
   }

   private static WatershedPlan decodeUnit(Path path, byte[] bytes, WatershedStore.UnitKey key) throws IOException {
      if (bytes.length < 44) {
         throw new IllegalStateException("Invalid watershed unit size: " + path);
      }

      int bodyLength = bytes.length - 4;
      CRC32 crc = new CRC32();
      crc.update(bytes, 0, bodyLength);

      try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
         if (in.readInt() != 1465079089 || in.readUnsignedByte() != 1) {
            throw new IllegalStateException("Unknown watershed unit: " + path);
         }

         if (in.readLong() != key.seed()
            || in.readInt() != key.originX()
            || in.readInt() != key.originZ()
            || in.readInt() != key.width()
            || in.readInt() != key.height()
            || in.readInt() != key.tileSizeBlocks()
            || in.readInt() != key.scale()
            || in.readInt() != key.generatorVersion()) {
            throw new IllegalStateException("Foreign watershed unit: " + path);
         }

         int fpLength = in.readUnsignedShort();
         if (fpLength > 512 || fpLength > in.available() - 8) {
            throw new IllegalStateException("Invalid watershed unit fingerprint: " + path);
         }

         byte[] fp = in.readNBytes(fpLength);
         if (!Arrays.equals(fp, key.fingerprint().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Foreign watershed unit fingerprint: " + path);
         }

         long cells = (long)key.width() * key.height();
         if (cells > in.available() / 4) {
            throw new IllegalStateException("Truncated watershed unit: " + path);
         }

         int[][] base = new int[key.height()][key.width()];

         for (int z = 0; z < key.height(); z++) {
            for (int x = 0; x < key.width(); x++) {
               base[z][x] = in.readInt();
            }
         }

         if (in.available() != 4 || in.readInt() != (int)crc.getValue()) {
            throw new IllegalStateException("Corrupt watershed unit: " + path);
         } else {
            WatershedPlan plan = WatershedPlan.planExtent(key.seed(), key.originX(), key.originZ(), key.width(), key.height(), base);
            if (plan.generatorVersion() != key.generatorVersion()) {
               throw new IllegalStateException("Watershed unit version drift (no silent mixing): " + path);
            } else {
               return plan;
            }
         }
      } catch (IOException malformed) {
         throw new IllegalStateException("Corrupt watershed unit: " + path, malformed);
      }
   }

   private static byte[] planPayload(WatershedPlan plan, WatershedStore.WatershedKey key, byte[] fingerprint) throws IOException {
      int[][] base = plan.coarseBase();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + fingerprint.length + base.length * base[0].length * 4);

      try (DataOutputStream out = new DataOutputStream(bytes)) {
         out.writeInt(1465075761);
         out.writeByte(1);
         out.writeLong(key.seed());
         out.writeInt(key.tileX());
         out.writeInt(key.tileZ());
         out.writeInt(key.tileSize());
         out.writeInt(key.scale());
         out.writeInt(key.generatorVersion());
         out.writeShort(fingerprint.length);
         out.write(fingerprint);
         out.writeInt(plan.coarseOriginX());
         out.writeInt(plan.coarseOriginZ());
         out.writeInt(plan.coarseWidth());
         out.writeInt(plan.coarseHeight());

         for (int[] row : base) {
            for (int value : row) {
               out.writeInt(value);
            }
         }
      }

      return bytes.toByteArray();
   }

   private static WatershedPlan decode(Path path, byte[] bytes, WatershedStore.WatershedKey key) throws IOException {
      if (bytes.length < 40) {
         throw new IllegalStateException("Invalid watershed sidecar size: " + path);
      }

      int bodyLength = bytes.length - 4;
      CRC32 crc = new CRC32();
      crc.update(bytes, 0, bodyLength);

      try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
         if (in.readInt() != 1465075761 || in.readUnsignedByte() != 1) {
            throw new IllegalStateException("Unknown watershed sidecar: " + path);
         }

         if (in.readLong() != key.seed()
            || in.readInt() != key.tileX()
            || in.readInt() != key.tileZ()
            || in.readInt() != key.tileSize()
            || in.readInt() != key.scale()
            || in.readInt() != key.generatorVersion()) {
            throw new IllegalStateException("Foreign watershed sidecar: " + path);
         }

         int fpLength = in.readUnsignedShort();
         if (fpLength > 512 || fpLength > in.available() - 20) {
            throw new IllegalStateException("Invalid watershed fingerprint length: " + path);
         }

         byte[] fp = in.readNBytes(fpLength);
         if (!Arrays.equals(fp, key.fingerprint().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Foreign watershed art fingerprint: " + path);
         }

         int originX = in.readInt();
         int originZ = in.readInt();
         int width = in.readInt();
         int height = in.readInt();
         if (width < 1 || width > 2048 || height < 1 || height > 2048) {
            throw new IllegalStateException("Invalid watershed extent: " + path);
         }

         long cells = (long)width * height;
         if (cells > in.available() / 4) {
            throw new IllegalStateException("Truncated watershed sidecar: " + path);
         }

         int[][] base = new int[height][width];

         for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
               base[z][x] = in.readInt();
            }
         }

         if (in.available() != 4 || in.readInt() != (int)crc.getValue()) {
            throw new IllegalStateException("Corrupt watershed sidecar: " + path);
         } else {
            WatershedPlan plan = WatershedPlan.planExtent(key.seed(), originX, originZ, width, height, base);
            if (plan.generatorVersion() != key.generatorVersion()) {
               throw new IllegalStateException("Watershed sidecar version drift (no silent mixing): " + path);
            } else {
               return plan;
            }
         }
      } catch (IOException malformed) {
         throw new IllegalStateException("Corrupt watershed sidecar: " + path, malformed);
      }
   }

   private void writeDecision(Path path, TerrainTileKey key, int worldScale, int generatorVersion, WatershedStore.Decision decision, byte[] fingerprint) throws IOException {
      byte[] data;
      try (
         ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + fingerprint.length);
         DataOutputStream out = new DataOutputStream(bytes);
      ) {
         out.writeInt(1465074737);
         out.writeByte(1);
         out.writeLong(key.seed());
         out.writeInt(key.tileX());
         out.writeInt(key.tileZ());
         out.writeInt(key.tileSize());
         out.writeInt(worldScale);
         out.writeInt(generatorVersion);
         out.writeShort(fingerprint.length);
         out.write(fingerprint);
         out.writeByte(decision == WatershedStore.Decision.ACTIVE ? 1 : 0);
         out.flush();
         data = bytes.toByteArray();
      }

      CRC32 crc = new CRC32();
      crc.update(data);
      Files.createDirectories(this.directory);
      Path var32 = Files.createTempFile(this.directory, ".hydro-dec-", ".tmp");
      boolean moved = false;

      try {
         try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(var32))) {
            out.write(data);
            out.writeInt((int)crc.getValue());
         }

         try {
            Files.move(var32, path, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(var32, path);
         }

         moved = true;
      } finally {
         if (!moved) {
            Files.deleteIfExists(var32);
         }
      }
   }

   private static WatershedStore.Decision readDecision(Path path, TerrainTileKey key, int worldScale, int generatorVersion) throws IOException {
      byte[] bytes;
      try (InputStream in = Files.newInputStream(path)) {
         bytes = in.readNBytes(1025);
      }

      if (bytes.length <= 1024 && bytes.length >= 40) {
         int bodyLength = bytes.length - 4;
         CRC32 crc = new CRC32();
         crc.update(bytes, 0, bodyLength);

         try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1465074737 || in.readUnsignedByte() != 1) {
               throw new IllegalStateException("Unknown hydro decision marker: " + path);
            } else if (in.readLong() != key.seed()
               || in.readInt() != key.tileX()
               || in.readInt() != key.tileZ()
               || in.readInt() != key.tileSize()
               || in.readInt() != worldScale
               || in.readInt() != generatorVersion) {
               throw new IllegalStateException("Foreign hydro decision marker: " + path);
            } else {
               int fpLength = in.readUnsignedShort();
               if (fpLength > 512 || fpLength > in.available() - 5) {
                  throw new IllegalStateException("Invalid hydro fingerprint length: " + path);
               } else {
                  byte[] fp = in.readNBytes(fpLength);
                  if (!Arrays.equals(fp, key.fingerprint().getBytes(StandardCharsets.UTF_8))) {
                     throw new IllegalStateException("Foreign hydro art fingerprint: " + path);
                  } else {
                     int encoded = in.readUnsignedByte();
                     if (encoded != 0 && encoded != 1) {
                        throw new IllegalStateException("Invalid hydro decision value: " + path);
                     } else if (in.available() != 4 || in.readInt() != (int)crc.getValue()) {
                        throw new IllegalStateException("Corrupt hydro decision marker: " + path);
                     } else {
                        return encoded == 1 ? WatershedStore.Decision.ACTIVE : WatershedStore.Decision.DISABLED;
                     }
                  }
               }
            }
         } catch (IOException malformed) {
            throw new IllegalStateException("Corrupt hydro decision marker: " + path, malformed);
         }
      } else {
         throw new IllegalStateException("Invalid hydro decision marker size: " + path);
      }
   }

   static {
      for (int i = 0; i < PLAN_LOCKS.length; i++) {
         PLAN_LOCKS[i] = new Object();
      }
   }

   public enum Decision {
      ACTIVE,
      DISABLED;
   }

   private record Journal(int sidecarCrc, List<long[]> daughters, List<Long> contracted) {
   }

   public record UnitKey(long seed, String fingerprint, int originX, int originZ, int width, int height, int tileSizeBlocks, int scale, int generatorVersion) {
      public UnitKey(long seed, String fingerprint, int originX, int originZ, int width, int height, int tileSizeBlocks, int scale, int generatorVersion) {
         if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must be non-blank");
         }

         if (width < 1 || width > 2048 || height < 1 || height > 2048) {
            throw new IllegalArgumentException("unit must be 1..2048 cells, got " + width + "x" + height);
         }

         if (tileSizeBlocks < 2 || tileSizeBlocks > 4096) {
            throw new IllegalArgumentException("tileSizeBlocks must be 2..4096, got " + tileSizeBlocks);
         }

         if (scale > 0 && generatorVersion > 0) {
            this.seed = seed;
            this.fingerprint = fingerprint;
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.height = height;
            this.tileSizeBlocks = tileSizeBlocks;
            this.scale = scale;
            this.generatorVersion = generatorVersion;
         } else {
            throw new IllegalArgumentException("scale and generatorVersion must be positive");
         }
      }
   }

   @FunctionalInterface
   interface UnitSidecarFault {
      void run() throws IOException;
   }

   public record WatershedKey(long seed, String fingerprint, int tileX, int tileZ, int tileSize, int scale, int generatorVersion) {
      public WatershedKey(long seed, String fingerprint, int tileX, int tileZ, int tileSize, int scale, int generatorVersion) {
         if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must be non-blank");
         }

         if (tileSize < 16 || tileSize > 1024) {
            throw new IllegalArgumentException("tileSize must be 16..1024, got " + tileSize);
         }

         if (scale > 0 && generatorVersion > 0) {
            this.seed = seed;
            this.fingerprint = fingerprint;
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.tileSize = tileSize;
            this.scale = scale;
            this.generatorVersion = generatorVersion;
         } else {
            throw new IllegalArgumentException("scale and generatorVersion must be positive");
         }
      }
   }
}

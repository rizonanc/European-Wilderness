package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Versioned, atomic sidecars; never edits Minecraft region files. */
public final class PreparedWaterStore {
    public static final int MAX_AREAS=4;
    private static final int MAGIC=0x45574831, VERSION=3;
    private final Path directory;
    private final long seed;
    private final String fingerprint;
    public PreparedWaterStore(Path directory,long seed,String fingerprint) {
        this.directory=directory; this.seed=seed; this.fingerprint=Objects.requireNonNull(fingerprint);
    }
    public List<PreparedWaterArea> loadAll() throws IOException {
        if(!Files.exists(directory)) return List.of();
        List<Path> paths;
        try(var files=Files.list(directory)) {paths=files.filter(p->p.getFileName().toString().endsWith(".ewh")).sorted().toList();}
        if(paths.size()>MAX_AREAS) throw new IOException("Too many prepared water areas");
        List<PreparedWaterArea> result=new ArrayList<>();
        for(Path path:paths) {
            PreparedWaterArea area=load(path);
            if(result.stream().anyMatch(area::overlaps)) throw new IOException("Overlapping saved water areas");
            result.add(area);
        }
        return List.copyOf(result);
    }
    public void save(PreparedWaterArea area) throws IOException {
        Files.createDirectories(directory);
        Path target=directory.resolve("water_"+area.x()+"_"+area.z()+".ewh");
        if(Files.exists(target)) throw new IOException("Water area is already saved");
        Path temp=Files.createTempFile(directory,"water-",".tmp");
        try {
            try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(seed); out.writeUTF(fingerprint);
                out.writeInt(area.x());out.writeInt(area.z());out.writeInt(area.side());
                for(int value:area.firstAirCopy()) out.writeInt(value);
                for(int value:area.waterCopy()) out.writeInt(value);
                out.writeInt(area.waterfalls().size());
                for(var f:area.waterfalls()) {
                    out.writeInt(f.x());out.writeInt(f.z());out.writeInt(f.dx());out.writeInt(f.dz());
                    out.writeInt(f.upperY());out.writeInt(f.lowerY());out.writeInt(f.width());out.writeInt(f.run());
                }
                area.writeCurrents(out);
            }
            // Require an atomic publish so a restart cannot see a partially written area.
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
        } finally {Files.deleteIfExists(temp);}
    }
    private PreparedWaterArea load(Path file) throws IOException {
        try(var in=new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if(in.readInt()!=MAGIC) throw new IOException("Unsupported water sidecar: "+file);
            int version=in.readInt();if(version<1||version>VERSION) throw new IOException("Unsupported water sidecar: "+file);
            if(in.readLong()!=seed||!in.readUTF().equals(fingerprint)) throw new IOException("Water sidecar belongs to another world/profile: "+file);
            int x=in.readInt(),z=in.readInt(),side=in.readInt();
            if(side<32||side>PreparedWaterArea.MAX_SIDE||side%2!=0) throw new IOException("Invalid water area size");
            int[] air=new int[side*side],water=new int[side*side];
            for(int i=0;i<air.length;i++) air[i]=in.readInt();
            for(int i=0;i<water.length;i++) water[i]=in.readInt();
            List<Waterfall> falls=new ArrayList<>();
            if(version>=2) {
                int count=in.readInt();if(count<0||count>1) throw new IOException("Invalid waterfall count");
                for(int i=0;i<count;i++) falls.add(new Waterfall(in.readInt(),in.readInt(),in.readInt(),in.readInt(),
                    in.readInt(),in.readInt(),in.readInt(),in.readInt()));
            }
            short[] currents=version>=3?RiverCurrent.read(in,water.length):null;
            if(in.read()!=-1) throw new IOException("Trailing water area data"); // also checks gzip CRC
            return new PreparedWaterArea(x,z,side,air,water,falls,currents);
        } catch(IllegalArgumentException|ArithmeticException malformed) {throw new IOException("Invalid water sidecar: "+file,malformed);}
    }
}

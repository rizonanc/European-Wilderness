package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.tree.TreeSpecies;
import java.util.*;
import java.nio.file.*;

/** Deterministic queue work comparison. No renderer, sleeps, GPU or model inference. */
public final class SurfaceStreamProbe {
    public static void main(String[] args) throws Exception {
        int[] air = new int[1024], water = new int[1024];
        Arrays.fill(air, 101);
        Arrays.fill(water, Integer.MIN_VALUE);
        var trees = new LodColumn.ForestBuilder(new LodColumn(0, 0, -64, 1967, air, water, new short[1024]));
        trees.set(16, 140, 16, TreeSpecies.OAK, false);
        var column = trees.build();
        var sink =
                new LodPublisher.Sink() {
                    public boolean active() {
                        return true;
                    }

                    public boolean occupied(int x, int y, int z) {
                        return false;
                    }

                    public void write(LodColumn c, int y) {}
                };
        int count = 128, oldSteps = 0, oldWrites = 0, newSteps = 0, newWrites = 0;
        for (int i = 0; i < count; i++) {
            var cursor = new LodPublisher.Cursor(column, true);
            boolean complete = false;
            while (!complete) {
                oldSteps++;
                for (int checks = 0; checks < 8; checks++) {
                    var result = cursor.step(sink, () -> true);
                    oldWrites += result.written();
                    complete = result.complete();
                    if (complete || result.written() > 0 || result.skipped() == 0) break;
                }
            }
        }
        var scan = new LodScan();
        scan.move(0, 0, 512);
        int completed = 0;
        while (completed < count) {
            var cell = scan.next();
            var progress = scan.progress(cell);
            boolean fresh = progress == null;
            var cursor = fresh ? LodPublisher.Cursor.visible(column, -3) : LodPublisher.Cursor.resume(column, progress);
            boolean complete = false;
            do {
                newSteps++;
                for (int checks = 0; checks < 8; checks++) {
                    var result = cursor.step(sink, () -> true);
                    complete = result.complete();
                    newWrites += result.written();
                    if (complete || result.written() > 0 || result.skipped() == 0) break;
                }
            } while (fresh && !complete);
            if (fresh) {
                completed++;
                scan.surfaceCompleted(cell, cursor.progress());
            } else if (complete) scan.completed(cell);
            else scan.surfaceCompleted(cell, cursor.progress());
        }
        String result =
                "{\"columns\":"
                        + count
                        + ",\"oldStepsUntilAllSurfaces\":"
                        + oldSteps
                        + ",\"newStepsUntilAllSurfaces\":"
                        + newSteps
                        + ",\"oldWrites\":"
                        + oldWrites
                        + ",\"newWrites\":"
                        + newWrites
                        + ",\"scope\":\"flat y100 with y140 crowns; sky empty; fixed queue operations, not measured game FPS\"}\n";
        Files.writeString(Path.of(args[0]), result);
        System.out.print(result);
    }
}

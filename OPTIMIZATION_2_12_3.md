# 2.12.3 — CPU generation optimization and water frame capture

Minecraft 1.21.11 / Fabric / Windows; 2 November 2026.

## Changes

- Hydrology flood queue stores packed primitive longs in the existing fastutil heap,
  preserving exactly the old signed-height, Z, X ordering. Breadth-first queues
  store primitive ints. No new native dependency.
- Accumulation processes incoming tributaries before their downstream cell in O(N).
  The former boxed O(N log N) sort and separate pour-distance grid are unnecessary.
  Every contribution is a positive integer count; order of independent tributaries
  cannot change the sum. A processed-cell check still rejects cycles.
- Private DrainagePlan construction transfers fresh owned arrays. Public accessors
  still return copies; original terrain arrays cannot mutate a plan.
- Density sampling caches the final automatic-water surface for four 16x16 windows
  per worker (about 8 KiB). Repeated samples at different Y no longer re-read the
  same water plan and raw window. Bulk fill uses the same path. Cache keys include
  the world UUID, immutable water controller UUID and raw lookup identity.
  No session/controller object is retained. Incomplete input and mutable manual
  overlays bypass this cache. Existing raw-window caching remains bounded.

## Checks and measured limits

286 JUnit tests pass. 448 exact comparisons against the released 2.12.2 planner
cover both routing APIs, rectangular/flat/basin/noisy terrain and signed integer
extremes. Filled heights, cell kinds, directions and accumulation all match.
The recorded terrain route also produces 22 byte-identical saved water files.
No profile fingerprint, plan revision, terrain model or precision changed.

Offline median CPU measurements (same data, 512x512 prepared drainage, nine
alternating measurements after warmup, elapsed time includes allocation):

| Input | 2.12.2 ms | 2.12.3 ms | Allocated bytes old / new |
|---|---:|---:|---:|
| Flat | 117.537 | 26.799 | 39,864,040 / 7,591,104 |
| Noisy | 437.636 | 150.686 | 49,822,304 / 19,836,816 |
| Smooth | 278.284 | 108.374 | 47,704,888 / 16,015,048 |
| Basin | 146.741 | 36.684 | 52,603,016 / 11,536,368 |

An offline automatic-water density workload (2 million requests, seven samples,
median) takes 60.736 ms / 6,000,000 allocated bytes with the old sampler and
13.848 ms / 0 bytes after warmup with the new one. Checksums match (-156737792).
These are algorithm measurements, **not** a 2.6–4.4x claim for Chunky or FPS.
The game and CPU probes were running during part of the work; absolute timings
can vary with concurrent load. Full-game before/after cps is still needed.
Evidence and runnable probes: ../recovery-evidence/hydrology-opt-v2123/.

## Live baseline and remaining work

The user's Chunky workload was observed on 2.12.2 and recorded in a 45-second
JFR. At the first active snapshot, a newly computed water unit averaged 647.46 ms;
raw terrain/inference waits and vanilla/C2ME density/lighting also contribute.
Sampling highlighted WorldWaterController.tile and DensityWindowCache.get, which
motivated the final-surface cache. Sampling counts are not timings or CPU percentages.

The shipped ONNX runtime reports 1.26.0 with CPU and DIRECT_ML providers only.
Three model sessions were resident in the game, with no evictions or memory fallback.
Base runWall averaged about 142 ms and decoder about 74 ms at that snapshot;
most input preparation/output copies were about 0.1 ms. Buffer reuse, priority
scheduling, single-flight and RAM/disk caches already exist. Zero-copy input
rewrites are not justified by those copy timings. FP16, adaptive resolution and
batching would require output/model compatibility experiments, not a silent switch.
A CUDA comparison needs an isolated compatible CUDA-enabled runtime; installing a
Rust skill cannot supply the missing provider. No CUDA speedup is claimed.

## Distant water — not fixed yet

The user reports a player-centred circle: ordinary nearby water appears, distant
water disappears or changes with angle. Photon, Seam Fix and Millida frame-generation
A/B tests previously failed to resolve it. Do not repeat them as a presumed fix.
Live Voxy metadata had nine existing water states, all baked, none missing the top.
The atlas's four allocated mip levels (0..3) all contain alpha 180 for water.
An attempted read of level 4 was invalid because that level is not allocated;
zeroes from that read are **not** evidence of an empty distant-water mip.
A one-frame inspection observed translucent commands and depth changes, but did
not establish correct final water composition. A later capture had no render frame
while the user left the world. Temporary diagnostic transformers were restored
and removed; no hook or background diagnostic loop was left running.

For the next concrete check this build adds an explicit one-frame command:

1. Install the supplied 2.12.3 JAR, leaving a single Terrain Diffusion version.
2. With Voxy enabled and the missing water visible, run `/td-voxy water-trace`.
3. Leave the view active for a second, then run `/td-voxy status`.

The `water-frame:` log records draw counters, the currently bound framebuffer
attachments, bounding/depth samples and both translucent color/data targets.
No shader, texture, Voxy setting or world data is changed. The optional mixin only
applies to pinned Voxy 0.2.16-beta / +1.21.11, at the end of its MDIC translucent
pass. Without a requested trace it only checks one flag. Unknown versions and no
Voxy do not activate the mixin. Missing frames expire after 15 seconds.
The command can cause a one-time GPU synchronization; it does not run every frame.
Its target method and fields are checked against the actual pinned JAR. Full live
validation of this newly built mixin is pending installation.

No installed mod/config/save/cache was replaced automatically. The agent did not
start, cancel or restart Chunky; the user started the profiled task.

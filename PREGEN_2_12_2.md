# 2.12.2 — locality for full-chunk pregeneration

`/td-voxy chunky <radiusChunks>` still invokes the public Chunky 1.4.55 API.
Its radius remains in chunks (1..2048); it now requests the native `region`
pattern rather than overriding Chunky's default with `concentric`. Confirmation
shows the selected pattern and approximate total area in chunks.

The region pattern processes neighbouring chunks together using Chunky's own
iterator. It reduces repeated eviction of our terrain tiles and watershed plans
while retaining the same circle, centre and radius. Already running or paused
tasks are left intact, including their saved pattern; this update affects new
tasks only. It never cancels/restarts a task, edits Chunky's task file or deletes
world/LOD data. A natively started Chunky job already using `region` does not gain
anything from the command's pattern change.

The biome source also keeps a thread-local cache of four chunks of horizontal
biome IDs (64 shorts, plus coordinate/validity arrays and a session UUID). The
generator's biome lookup does not depend on Y, so Minecraft's repeated queries
on vertical biome cells reuse the same ID. Session changes invalidate the cache;
prepared-only fallback IDs are never cached. No world, repository, raw tile or
registry is retained in a cache value. Neural output, terrain, water and tree
algorithms and world fingerprints are unchanged.

## Evidence and limits

- `ChunkyLocalityProbe.java` uses the **actual installed 1.4.55** iterators and
  circle predicate, centre (1332,822), radius 4096 blocks / 256 chunks. Both
  patterns produce exactly the same set of 206709 chunk coordinates.
- Simulated LRU misses for 48 raw tiles / 16 water cores:
  `concentric`: 10369 / 5986; `region`: 870 / 237. This is a locality model, not
  a full terrain generation speed benchmark; it omits dependencies and C2ME's
  concurrent scheduling.
- A warm two-million-query microbenchmark of the existing repository path versus
  the biome column cache returned matching checksums. Measured second round:
  110.284ms / 160000000 allocated bytes versus 9.480ms / 0 allocated bytes.
  This isolates biome-query cost and does **not** imply 11.6x faster full chunks.
- Full JUnit/build results and release hashes are in the release manifest.
- No game/profile/cache edits or pregeneration were started by the agent. Total
  cps / ETA improvement with this candidate has not yet been measured in game.

Chunky already enters Minecraft's real generation pipeline, including our neural
terrain provider. It also creates blocks, features, structures, lighting and save
data, so it cannot consume a height map as a finished chunk. A circle of radius
2048 chunks covers about 13.2 million full chunks. It has 64 times the area of
radius 256. An early ETA fluctuates with biome, cold inference, cache and IO load;
this release does not promise minutes for that workload.

## Distant-water investigation remains open

The user tested the separate Photon depth candidate and still sees absent water,
including a no-Photon comparison: water is visible near a screen edge after a
camera rotation. Read-only config confirmed `enableShaders=false`. This rules
out treating the previous Photon correction as the complete fix. Its depth-order
change is not bundled in this mod release.

Native chunk source is active: our own coarse/NN LOD is disabled. The pinned
Voxy source has native Chunky ingestion and the native converter preserves fluid
block states. The installed Voxy Seam Fix 0.2.1 independently patches fluid quad
positions and the rendering uniform buffer. Static review has not proven it is
the cause. Its installed metadata reports MIT/HTRMC; do not confuse it with a
similarly named ARR Modrinth package or replace it using an unverified download.

The user subsequently tested with Seam Fix disabled and the bug remains.
Read-only inventory confirms its JAR is `.disabled`. It is not the sole cause.
The active Millida profile had frame generation x3 enabled; the user also tested
with frame generation disabled and the bug remains. This is not the sole cause
either. No addon/config/cache was changed
automatically, no further speculative water patch was shipped, and water is
**not fixed** by 2.12.2. Angle-dependent appearance indicates a rendering/
visibility candidate, but does not alone identify the responsible stage.

`/td-voxy status` now adds a read-only `water-model` report and logs that line.
It reads existing Voxy palette entries and baked model metadata: renderer
pipeline, water-state count, baked count, missing top faces and metadata samples.
It never registers a state, requests a model bake, starts generation, writes
Voxy storage or changes GL state. A reported present top face does not establish
visible pixels; it narrows the next investigation to rendering/culling rather
than missing model geometry. Reflection is pinned to 0.2.16 and fails to an
unavailable message. The original constructor startup guard remains in force.

Sources:

- https://github.com/pop4959/Chunky/wiki/Patterns
- https://github.com/pop4959/Chunky/wiki/Developer-API
- Pinned Voxy source under `recovery-evidence/voxy-compat/references/source/`.

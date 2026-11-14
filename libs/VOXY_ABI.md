# Optional Voxy compile ABI

The local file `voxy-0.2.16-beta+1.21.11.jar` is a compile/test-only dependency.
It is not included in the released Terrain Diffusion JAR or source archive.

To build from the source archive, copy your installed Voxy 0.2.16-beta for
Minecraft 1.21.11 into this directory under that name. Verified SHA-256:

`7f8226cf64a4f5dc064e1075965714b718ac5f42fb28f826135a37c64e66e10b`

Official project: https://modrinth.com/mod/voxy
Published API source used for matching the ABI:
https://github.com/MCRcortex/voxy/tree/59b62bee821518e06612e1d5c2c58c487bda761d

No Voxy implementation classes or shader assets are copied into this mod.
The optional mixins apply only to the pinned supported version on the client.

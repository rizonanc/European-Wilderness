# European Wilderness

European Wilderness is a world-generation mod for Minecraft that builds European-inspired landscapes with high mountain ranges, flowing rivers, broad forests, and detailed coastlines.

- Follow rivers from inland valleys to lakes, waterfalls, and the sea. Currents can carry boats and dropped items.
- Explore snowy summits, marked mountain passes, limestone sinkholes, fjords, and rocky skerries.
- Find tropical archipelagos and desert oases with palms.
- Tune terrain and biome proportions when creating a world, or choose your starting area in European Wilderness Explorer.

## Installation

**Minecraft 1.21.11 · Fabric Loader 0.18.1 or newer · Fabric API · Java 21 · Windows x64**

Put the European Wilderness JAR in `mods/` alongside Fabric API, then select **European Wilderness** as the world type when creating a world. The first launch needs an internet connection and roughly 2.3 GB of disk space for the terrain models. The Windows build includes ONNX Runtime with DirectML support.

Mod Menu is optional and provides access to the settings screen. Voxy, Road Weaver and Wilder Wild are optional. The tested integration targets are Voxy 0.2.16-beta and Road Weaver 2.2.0 for Minecraft 1.21.11.

## Upgrading

Replace the previous mod JAR with European Wilderness. It keeps the same internal mod and world-generation IDs for existing saves. Older configuration and menu preferences are copied to `config/european-wilderness.properties` and `config/european-wilderness-menu.json` on first use. An existing model directory is reused. Back up your world before updating a world-generation mod.

## Build from source

Use JDK 21 and run `./gradlew build` (Windows: `gradlew.bat build`). The JAR is written to `build/libs/`. The build resolves the pinned Voxy compile dependency from Modrinth if a local copy is unavailable. The build's DirectML runtime matches the one shipped in the original upstream Windows mod.

## Credits and license

European Wilderness extends [Terrain Diffusion MC by Alexander Goslin](https://github.com/xandergos/terrain-diffusion-mc) and uses [his ONNX terrain models](https://huggingface.co/xandergos/terrain-diffusion-30m-onnx). Thanks to [Joao](https://github.com/batata1-jg) for upstream DirectML support. See [the MIT license](LICENSE.txt) and [third-party notices](licenses/).

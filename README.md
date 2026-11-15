# European Wilderness

European Wilderness adds a Minecraft world type with high mountain ranges, forests, flowing rivers and rugged coastlines. Rivers carry boats and dropped items. Snowy passes have stone cairns, and the terrain includes fjords, skerries, limestone sinkholes, desert oases and tropical archipelagos.

## Install

Use Minecraft 1.21.11, Fabric Loader 0.18.1 or newer, Fabric API and Java 21 on Windows x64. Drop the JAR into `mods/` and choose **European Wilderness** when creating a world. The first launch downloads and verifies about 2.3 GB of terrain models. The Windows build ships with ONNX Runtime and DirectML support.

Adjust terrain and biome proportions during world creation, or choose a starting area in European Wilderness Explorer. Mod Menu gives you access to the mod settings. Voxy, Road Weaver and Wilder Wild are optional. The Voxy and Road Weaver integrations were tested with Voxy 0.2.16-beta and Road Weaver 2.2.0 for Minecraft 1.21.11.

## Upgrade

Replace the old mod JAR. Existing worlds keep their mod and world-generation IDs. On first use, the mod copies older settings to `config/european-wilderness.properties` and menu preferences to `config/european-wilderness-menu.json`. It reuses any existing model directory. Back up your world before updating.

## Build

Run `./gradlew build` with JDK 21, or `gradlew.bat build` on Windows. The JAR is in `build/libs/`. Gradle fetches the pinned Voxy dependency from Modrinth if a local copy is unavailable. The build bundles the upstream Windows DirectML runtime.

## License and credits

European Wilderness is distributed under the [MIT license](LICENSE.txt). It builds on Alexander Goslin's [Terrain Diffusion MC](https://github.com/xandergos/terrain-diffusion-mc) and [ONNX terrain models](https://huggingface.co/xandergos/terrain-diffusion-30m-onnx). Thanks to [Joao](https://github.com/batata1-jg) for upstream DirectML support. See the [third-party notices](licenses/).

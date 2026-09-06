# Minecraft Transit Railway for Minefed

Forked project of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway).

This project used at minefed server.

## Building

Use JDK 21 for Gradle, JDK 17 for the Minecraft 1.20.4 toolchain, and Node.js 22 with npm.
Run `./gradlew :fabric:remapJar --configure-on-demand -PminecraftVersion=1.20.4`.
The Fabric build installs the locked website dependencies, builds the website, and generates its embedded resources before compiling the mod.
The runtime JAR is written to `fabric/build/libs/fabric-4.0.5.jar`.

## Notice regarding changes
- Changes (additions, modifications, deletions) to this repository will only be added to the Fabric client.
- All changes to this repository are distributed under the MIT License, so you are free to use them as you wish.

## Disclaimer

- This project has not been officially released. if you are not using it on a Minefed server, we recommend using the official project linked above.
- We do not guarantee proper behavior in environments other than Minefed servers.

## License
- [License of this forked project](./LICENSE)
- [License of original project](./LICENSE_ORIGINAL)

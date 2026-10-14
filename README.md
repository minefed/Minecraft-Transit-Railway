# Minecraft Transit Railway for Minefed

Forked project of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway).

This project used at minefed server.

## Building

Use JDK 21 and Node.js 22 with npm. Java compilation uses `--release 17` for Minecraft 1.20.4, preserving Java 17 runtime compatibility.
Run `./gradlew :fabric:remapJar --configure-on-demand -PminecraftVersion=1.20.4`.
The Fabric build installs the locked website dependencies, builds the website, and generates its embedded resources before compiling the mod.
The runtime JAR is written to `fabric/build/libs/fabric-4.0.5.jar`.

## Public transit data

The server can publish selected MTR routes, vehicles, stations and arrivals to
`minefed-api`. BlueMap uses the same public API. See the
[transit API configuration and migration guide](docs/transit-api.md).
Existing public-route settings and the 500 ms / 2 s / 30 s refresh periods remain
in use; API publication requires an explicit server URL and environment token.

## Notice regarding changes
- Minefed changes target Minecraft 1.20.4 on Fabric, including server integrations and client rendering.
- All changes to this repository are distributed under the MIT License, so you are free to use them as you wish.

## Disclaimer

- This project has not been officially released. if you are not using it on a Minefed server, we recommend using the official project linked above.
- We do not guarantee proper behavior in environments other than Minefed servers.

## License
- [License of this forked project](./LICENSE)
- [License of original project](./LICENSE_ORIGINAL)

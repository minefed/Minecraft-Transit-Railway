# Signal scan and constant light-type overhead

These changes remove allocation/arithmetic overhead from the existing client paths. They do not cache world state, light values or selected signal nodes, and do not change render quality, culling, door behavior, or rendering cadence.

## Signal nodes

`RenderSignalBase.getNodePos` still makes all 891 block-state reads in z/x/y order. It constructs one final immutable position per cell, keeps the existing integer additions (including overflow), computes the existing Manhattan distance only for nodes, and retains the strict `<` comparison for equal-distance candidates. Clockwise direction and the center vector holder are prepared once per invocation.

The differential tests enter through `getAspectState` and `render`. The reference retains the original mapped offset chain and Manhattan calculation, so tests exercise real Minecraft coordinates and distance semantics rather than a second scalar reimplementation. They check every queried coordinate and block-type check, missing/equal-distance nodes, all four horizontal facings, negative/world-limit/integer-limit coordinates, subsequent edits and missing worlds, final immutable position ownership, front/back aspect calls and the final redstone update's ordered rail IDs.

## Light types

Known `getBlockMapped()` / `getSkyMapped()` call sites use the identical `LightType.BLOCK` / `LightType.SKY` singletons directly. Every block/sky light sample remains fresh and ordered. No public enum API changes.

`LightSamplingEquivalenceTest` traces repeated public `PositionAndRotation` construction, including changed values and missing worlds. `LightTypeContractTest` verifies singleton identity against the actual resolved dependency and records its archive path and SHA-256. It additionally checks the helper/initializer/ordinal contract and all eight changed sampling sites in the compiled classes and final remapped JAR. `verifyLightTypeCompatibility` is part of `check`, alongside existing rail and mixin artifact checks.

## Validation

Use the project's Java 21 build toolchain, which emits Java 17 bytecode:

```
./gradlew --configure-on-demand :fabric:test :fabric:build :fabric:verifyRailRendering :fabric:verifyMixinCompatibility :fabric:verifyLightTypeCompatibility
```

The test runtime includes Fabric Loader JUnit at the same pinned version as Fabric Loader. Its launcher-session listener initializes Knot before tests, including Minecraft's named-package access transformations. This is required for the signal fixture's real registry bootstrap: a plain application classloader fails when `SimpleRegistry` calls the package-access `RegistryEntry.Reference.setRegistryKey` method after those classes have been remapped into different packages. Keep the real bootstrap and equivalence assertions intact. See [Fabric's unit-testing setup](https://docs.fabricmc.net/develop/automatic-testing).

The tests mock world/client boundaries; they are not an in-game visual, packet, or performance measurement. The artifact contract checks fail when a future Mappings dependency changes the assumed singleton behavior. Forge needs a separate loader build/runtime check before claiming a verified Forge distribution. No FPS or runtime byte-allocation reduction is claimed without profiling; HotSpot may already eliminate some temporary allocations.

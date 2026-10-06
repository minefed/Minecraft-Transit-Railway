# Vehicle light sampling

`RenderVehicles` still calculates every car's current position and rotation for
culling and riding offsets. It now samples block and sky light only after the
existing `rayTracing[carNumber] || isRiding(vehicleId)` visibility condition passes.
The resulting packed light is shared by the car body and bogie rendering paths.
Each visible car still reads block light followed by sky light at the original
floored `(x, y + 1, z)` position in every render invocation, including shadow passes.
No light value is cached between frames or shared with another car.

The package-private geometry factory skips the light read without changing the
position, yaw or pitch arithmetic. Public `PositionAndRotation` constructors and
their final `light` field retain their eager sampling contract. The renderer uses
a local sampled value, so this adds neither a second position object nor a cache
to visible cars. Riding cars remain visible regardless of their culling result;
the visibility condition is evaluated at its original place in the car loop.

This removes two world-light queries per occluded, unridden car per render
invocation. Vehicle smoothing, culling inputs, culling scheduling, render order,
door checks, boarding, movement and sounds are unchanged. Visible samples occur
immediately before the resource lookup instead of during the earlier geometry
preparation. Model callbacks receive the same freshly packed value for that car.

`VehicleLightSamplingTest` exercises the actual render entry point with two cars,
without loading a GPU model. It checks hidden/visible/hidden transitions, changing
light levels, the riding override, ordinary and shadow passes, negative coordinate
flooring, and continued smoothing of all cars. A separate geometry comparison
covers zero through three bogies, both pitch modes, eager public construction, and
the missing-world fallback. The existing light contract checks run against both
resolved classes and the remapped Java 17 distribution.

Validation on 2026-10-07, Minecraft 1.20.4 / Fabric Loader 0.18.4: all 91
`:fabric:test` tests, 8 rail checks, 8 Mixin compatibility checks and 3 light
compatibility checks passed with no failures or skipped tests, using Java 17.
The release recipe built the remapped JAR; comparison with the previous artifact
found only `PositionAndRotation`, `RenderVehicles` and its three nested class
files changed. All five target Java 17, and resources and bundled dependencies
are byte-for-byte unchanged.

No new in-game FPS or allocation improvement has been measured. Gameplay checking
should include entering and leaving a tunnel, occlusion transitions, boarding a
previously hidden train, shader shadows, and changed lighting at fixed car positions.
Forge's source-copy path includes the common change but is not separately validated.

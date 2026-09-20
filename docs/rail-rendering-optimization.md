# 3D rail rendering (Minecraft 1.20.4)

This change targets the Minefed Fabric 1.20.4 client with Loader 0.18.4 and the
bundled Minecraft-Mappings renderer. The same matrix bridge is provided for
Forge 1.20.4. It uses the JOML matrix API of these Minecraft versions; older
pre-JOML Minecraft targets require their own bridge before backporting.

## Complete render path

1. `MinecraftClientData.sync()` maintains `RailWrapper` objects for received
   rails. `MainRenderer.render()` runs once per world render pass, simulates
   vehicles, renders vehicles/lifts, then calls `RenderRails.render()`.
2. `RenderRails` schedules asynchronous whole-rail AABB occlusion tests on
   `WorkerThread`. Completed results are applied on the client thread. Visible
   rails are combined with brush/build previews; tools also enable coloured
   surfaces, one-way arrows, signals and node markers. MSD injects its catenary
   rendering at the head of `RenderRails.render()`; that entry point is retained.
3. `renderRailStandard()` resolves each style, including the default 3D track
   and siding substitutions and `_2` reverse direction. Each `RailResource`
   supplies the repeat interval, vertical offset and cached model.
4. `RailMath.render()` samples the two horizontal curve pieces and vertical
   profile. The original loop checks horizontal render distance, then a camera
   hemisphere beyond 32 blocks. It queries block/sky light at the segment start
   plus 0.1 Y and places the model at the midpoint. Rotation is Y (heading),
   X (slope / model inversion), then a small Z tilt from the start coordinates.
5. Originally every visible piece created a `StoredMatrixTransformations`, a
   list/rotation lambda and a render callback, and queried both cached models.
   The queued callback pushed/copied the position and normal matrices,
   translated and rotated both matrices three times, queued the model and
   popped the stack.
6. `MainRenderer` drains its double-buffered render queue by stage/layer/texture.
   `OptimizedRendererWrapper.queue()` delegates to Minecraft-Mappings:
   `OptimizedRenderer.queue()` snapshots the position matrix into
   `VertexAttributeState`; `BatchManager` groups `RenderCall`s by material.
   At frame end the optimized renderer submits those calls. This patch batches
   CPU scheduling. The later frustum filter also removes off-screen submissions;
   visible pieces still use the original mesh and draw path.
7. The non-optimized path uses `DynamicVehicleModel`. 2D track surfaces, signals,
   arrows, cable lines and node markers use their original rendering paths.

## Change and correctness constraints

`RailGeometryCache` stores immutable placements keyed by **RailMath identity**,
repeat interval, model Y offset and reverse direction. A replaced geometry
object or changed resource parameters cannot hit the old entry. The LRU keeps
at most 131,072 placements and 2,048 rail/style entries; oversized rails retain
the streaming path. Ghost rails are excluded because their objects are rebuilt
on each frame. No cached entry retains a world, GPU model, light value or
visibility result.

`RailModelGeometry` computes segment positions and the three rotations once.
Coordinates remain doubles until after camera-offset subtraction, preserving
precision near the world border. `RailRenderView` uses the original Minecraft
sin/cos values and visibility rules, with a squared distance comparison and no
per-piece vectors. It is refreshed for every render pass, including shadows.

For each visible stable rail/style, `RenderRails` refreshes the model cache once
and schedules one callback. That callback pushes the stack once, restores its
base position matrix for each piece, applies a cached rotation, queries current
block/sky light and queues the original GPU model. A `finally` block restores
the caller's stack. The optimized renderer copies each matrix before the next
piece overwrites it. Its queue does not consume the MatrixStack normal matrix,
so the unused normal rotations and per-piece stack copies are eliminated.
The small loader-specific `GraphicsHolderRailMatrixMixin` exposes that position
matrix; it is registered on the client only.

`RawModelBoundsMixin` measures the actual transformed vertices at upload time,
covering Blockbench and OBJ/MQO resources. A weak registry passes conservative
origin-centered sphere radii to `OptimizedModelBoundsMixin`; combined models
take the maximum radius. The registry does not keep GPU objects alive. Models
created outside this upload path and invalid bounds fail open.

`RailModelFrustum` uses `projection * modelView * base`, matching the bundled
`PatchingResourceProvider` vertex shader. It subtracts the same double-precision
camera offset as model placement. Whole model spheres, including custom models,
must be outside the frustum before their lighting and queue work is skipped.
Bounds include a rounding margin, and degenerate projections fail open. Shader
shadow passes retain the previous visibility policy. Geometry and texture detail
are unchanged. The culler uses JOML's normalized sphere/plane intersection tests
([API](https://joml-ci.github.io/JOML/apidocs/org/joml/FrustumIntersection.html)).

Resource reload, client data reset, disconnect and world replacement clear the
cache. Existing tool previews and streaming fallback retain their original
appearance and dynamic-model behavior. Lighting remains current every frame;
camera movement never uses cached visibility. Resource handles are acquired
each frame so model expiry/reload cannot leave a cached stale GPU handle.

## Verification

`RailModelGeometryTest` compares cached placements to the actual Core sampler
and the original Minecraft MatrixStack sequence for straight/curved/uphill/
downhill rails, both directions, several repeat intervals and world-border
coordinates. It compares 10,000 camera cases to the original Vec3d rotations
and checks the 32-block near radius and render-distance boundaries. Separate
cases exercise replacement, all cache parameters, clearing and LRU limits.

`RailModelFrustumTest` checks screen/near-plane crossings, large custom bounds,
invalid data and 20,000 varied shader-space vertex cases, including slopes,
rotations, nonuniform base scales and world-border offsets. Any vertex on screen
must survive the model-level sphere test.

`MixinCompatibilityTest` checks the bridge against the matrix field in the
bundled/remapped GraphicsHolder and verifies the optimized renderer's matrix
snapshot/no-normal-matrix contract, in addition to existing Loader compatibility
checks. Run the focused checks without the asset-rewriting legacy model test:

```text
gradlew :fabric:verifyRailRendering :fabric:verifyMixinCompatibility
```

Live FPS/frametime results belong in the Minefed modpack's performance report.
Compare the same location, view, settings and resolution, distinguish VSync
conditions, and recheck visible tracks, lighting and resource reloads. Unit
tests establish geometric equivalence; they do not establish an FPS gain.

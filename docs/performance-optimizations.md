# Performance optimizations

These changes preserve update frequency, visibility distances, coordinate precision,
render order, and simulation behavior. They target repeated client work and the
Minecraft packet representation. No server or client configuration is required.

## Client work

- Render queues use insertion-ordered hash lookup and reuse empty callback lists.
  Each queue retains at most 64 lists and 4,096 backing-array slots. Queues still
  alternate between two buffers; texture keys and callbacks are cleared between
  uses.
- Rail wrappers and blocked rail IDs use hash lookup while retaining the public
  fastutil ArrayMap/ArraySet field types and insertion order.
- Vehicle/lift updates refresh their own indexes and wrappers. Full topology
  updates still rebuild the rail graph and spatial indexes. Only newly received
  vehicle paths need initialization during a vehicle update.
- Sensor filtering skips block-entity lookups for irrelevant block types. Sensor
  scanning and notifications run at the original times.
- Riding floor fallback searches are lazy. Unridden vehicles do not format the
  rider-only announcement/UI strings.
- PIDS caches pure string splitting/combination, with entry and text-size bounds,
  and reads the current station page without copying the remaining route.
  Arrival times, language/page changes, and display colors remain dynamic.
- Texture keys use the same string values without Formatter allocations. Model
  displays reuse text widths within one callback. Transform-only positions skip
  unused light lookups; actual model light sampling is unchanged.
- Door interpolation keys retain signed-zero/NaN distinctions without strings.
  Vehicle resource views are reused through weak references while all underlying
  cache lookups and lifetime updates still run.

## Packet codec version 1

`PacketRequestData` advertises `_mtrPacketCodec: 1` as an extra JSON field. Older
Core readers ignore this field. The first request is always in the legacy format.
A supporting server records support for that player's connection and echoes the
field in its data response. The client enables binary requests only after that
response. Until negotiation succeeds, or with an older peer, packets keep the
legacy JSON format. Support is cleared at disconnect and server shutdown.

The negotiated format applies to data requests/responses, riding updates, vehicle
and lift updates, and arrival responses. It changes representation only: keep and
update lists, array/object order, unknown fields, all seven riding flags, empty
rider lists, and trailing fields such as `dismount` are retained.

The existing packet class names and outer fragmentation protocol are unchanged.
Each encoded JSON object uses one of two payloads:

1. The original `PacketBufferSender.writeString(json)` format.
2. `writeString("\0MTR1")`, an `int` byte length, and binary bytes packed into
   `long` values followed by `char` values. An odd final byte is padded with zero.

The mapping's string format is an `int` UTF-16 code-unit count followed by chars,
not UTF-8. Binary is selected only when its entire payload, including its marker,
length, and padding, is smaller than that legacy representation. Minecraft's
compression remains unchanged; raw payload reductions are not a measurement of
compressed network traffic.

Binary trees use byte type tags (null=0, false=1, true=2, long=3, double=4,
number-text=5, string=6, array=7, object=8), unsigned LEB128 lengths/references,
zigzag longs, and big-endian IEEE-754 doubles. Canonical long/double representations
use numeric tokens; other numeric spellings retain their exact text. Strings use
the fixed dictionary in `BinaryPacketCodec` and then a per-packet dictionary.
Reference zero introduces a UTF-8 literal. Array/object counts precede their
elements; object keys are strings without an additional value-type tag.

The dictionary and framing must not change without a new negotiated codec version.
Unknown fields are encoded as literals rather than dropped. Unpaired UTF-16
surrogates, trees deeper than 128 levels, or encodings above 64 MiB fall back to
the legacy format. The decoder checks lengths, references, depth, and trailing
bytes. Binary encoding adds no lossy quantization and no second compression layer.

## Arrival snapshots

Server arrival JSON strings are reused only within the current snapshot. Replacing
the snapshot clears them, and changing an arrival's car-details list invalidates
that entry. Response time and callback ID are still computed for each request.
This saves serialization work without changing the legacy payload contents.

## Validation

Regression tests cover collection order/views/cloning/serialization, dynamic sync,
keep/update/remove behavior, path initialization, arrival-cache invalidation,
render queue isolation/pool bounds, PIDS parsing, resource lifetime, door keys,
codec precision, negotiation, and fragmented packet framing with trailing fields.

For the configured Minecraft version, run:

```text
gradlew :fabric:test --tests "org.mtr.mod.*" --tests "org.mtr.test.VehicleResourceCacheTest" --tests "org.mtr.test.DoorInterpolationKeyTest" --tests "org.mtr.test.MqoModelConverterTest" --tests "org.mtr.test.BlockbenchModelValidationTest.testRounding"
```

`BlockbenchModelValidationTest.validate` rewrites bundled model assets, so it is
intentionally separate from this regression command.

Validation on 2026-09-06, Minecraft 1.20.4: Fabric and Forge compilation passed
with Java 17 bytecode, all 42 selected tests passed, and `:fabric:remapJar`
completed. The synthetic 4,000-entry vehicle/ID fixture measured 1,123,994 legacy
bytes versus 160,093 binary bytes before compression (85.8% smaller). Applying
default zlib to each fixture fragment measured 175,732 versus 113,428 bytes
(35.5% smaller). This fixture is not a measurement of live server traffic or FPS.

On this Windows checkout, the Java 21 Gradle daemon needed `file.encoding=MS949`
to write classpath argument files readable by its Java 17 workers under a Korean
user path. A temporary init script kept Java source and test encodings at UTF-8.
The project build configuration and Java 17 target were not changed.

Live performance comparisons should use the same world, player count, vehicle
positions, view distance, resource pack, and input sequence. Compare RX/TX with
Minecraft compression enabled, average frame time and p99 frame time, then verify
boarding, manual controls, sensors, signals, PIDS language/page transitions,
lighting, resource reloads, and mixed-version connections. Unit tests do not
replace those in-game measurements.

## Door, occlusion and packet work (2026-09-28)

These changes remove repeated work only. Rendering, door behavior, culling
results, network bytes and server state are unchanged.

- **Door platform scan.** `canOpenDoors` scanned every cell around each open
  doorway of each stopped vehicle and lift on every frame, including shadow
  passes. `DoorScanCache` stores whether a platform or unlocked PSD/APG was
  found and the unlocked door cells, keyed by the exact floored cells visited by
  the original `double` loops. An entry is reused only while every chunk column
  it read is the same chunk object with the same door-scan epoch. The
  client-only `WorldChunkDoorScanMixin` bumps that epoch when a
  platform/PSD/APG block is placed, removed or changed, and when a chunk is
  reloaded from a packet. Unloaded or out-of-radius columns resolve to a
  different chunk object. Door values are still applied every call, to block
  entities looked up again. Ranges above 64 cells per axis and untracked
  chunks use the original loop. The LRU keeps 1,024 entries and is cleared on
  data reset, world change and disconnect.
- **Occlusion cache reset.** The worker reset its whole occlusion cache on
  every cycle (13.5 MiB at render distance 12, 256 MiB from 32).
  `DirtyBlockOcclusionCache` keeps the library's byte layout, index arithmetic
  and exceptions, marks 64-byte blocks that become non-zero, and zeroes only
  those. `ReachLimitedOcclusionCullingInstance` returns `false` directly for
  boxes whose cells all lie outside the cache cube, after the camera-inside
  check. The library marks each such cell skipped, because its cache lookup
  returns -1, and returns `false` without casting rays. Unsorted, oversized or
  near-overflow boxes still use the library.
- **Occlusion task lists.** Rails, vehicles and lifts do not build culling
  lambdas when the two-slot queue is already full. The offer would be dropped.
  If the worker polls between the check and the offer, that frame behaves as
  if it had polled just after the offer, which the original timing allowed.
- **Optimized renderer.** Mixins reuse the `VertexAttributeType` array and a
  per-thread 64-byte matrix buffer in `VertexAttributeState.apply()`, and
  compute the `Objects.hash` values of `VertexAttributeState` and
  `MaterialProperties` without varargs arrays. GL calls, their order and
  arguments are unchanged. Per-frame batch lists and VAO rebinding were left
  unchanged: pooling can change `HashMap` iteration order, and skipping binds
  changes the GL call sequence.
- **Station lookups.** `findStation` results are cached per block position in
  the client data instance, including misses. Core applies every station
  update, addition and removal before `sync()`, which clears the cache. The
  first matching station in iteration order is still returned. The railway
  sign route name separator is a precompiled `Pattern` with identical
  `split` semantics.
- **Sensor requests.** The client resent `PacketTurnOnBlockEntity` on every
  frame until the sensor's powered state arrived. The server's `power()` only
  raises the level to 2, so repeats were no-ops. A request for the same sensor
  is now sent again only when the observed power level changes or after
  250 ms.
- **Server vehicle updates.** `VehicleExtraData.copy` no longer builds the
  detached copy of the whole path that only became the copy's unused
  `immutablePath`. Its only caller serializes the copy from `path`. For binary
  peers, `PacketPayload` counts the characters of the legacy JSON with the same
  lenient `JsonWriter` instead of building the string only for a size check.
- **Not changed.** One `VehicleUpdate` object is still built for each client.
  Sharing it requires showing that no recipient-specific state or mutation can
  reach it.

Validation on 2026-09-28, Minecraft 1.20.4 Fabric with Java 17 bytecode:

- `DoorScanCacheTest` compares cached and uncached scans over 200,000 random
  steps with block edits, chunk unloads, reloads and replacements. It also
  checks the visited cells against the original loop and Minecraft's `floor`.
- `OcclusionCullingEquivalenceTest` compares every byte and exception with
  `ArrayOcclusionCache` and compares 180,000 random culling queries with the
  library instance.
- `MinecraftClientDataSyncTest`, `SensorPowerRequestsTest` and
  `PacketPayloadTest` cover station lookups across syncs, sensor request
  suppression and counted JSON lengths.
- `MixinCompatibilityTest` checks the new mixin registrations, refmap entries,
  bundled call sites, `hashCode` field order, and the single `copy` caller.

The regression command above passed all 68 selected tests, and
`:fabric:verifyRailRendering :fabric:verifyMixinCompatibility` passed against
the remapped JAR. Compared with the pre-change JAR, only MTR classes, the mixin
config and the refmap changed; bundled Core, Mappings and library classes are
identical. In a temporary Fabric Loader 0.18.4 JUnit/Knot run (MixinExtras
0.5.0), all mixins applied and a full Mixin audit passed. ForgeGradle could not
set up Forge here because its MCP step requires a Java 17 toolchain; the Forge
sources were instead compiled with `javac --release 17` against the Mojang-named
1.20.4 game and the Forge Mappings JAR.

A synthetic reset benchmark measured 0.6-1.7 ms for the full 13.5 MiB reset
and 0.08-0.30 ms after 20,000-200,000 touched cells. At the 256 MiB limit it
measured 39 ms versus 2.7 ms. These are not in-game FPS measurements.

## Fabric Mixin compatibility

The Fabric build pins `fabricLoaderVersion=0.18.4` in `gradle.properties`.
Previously the build fetched the latest Loader from Fabric's metadata service.
Loader 0.19.5 supplied a newer Mixin API in which `Redirect.at()` returns an array.
Compiling against that API encoded even a single `@At` as an array in both speed
limit mixins. Loader 0.18.4's MixinExtras 0.5.0 expects a single annotation and
failed while transforming `Siding`, before the server could start. Its
[factory redirect transformer](https://raw.githubusercontent.com/LlamaLad7/MixinExtras/0.5.0/src/main/java/com/llamalad7/mixinextras/wrapper/factory/FactoryRedirectWrapperMixinTransformer.java)
casts the stored `at` value directly to `AnnotationNode`.

The fixed compile dependency restores the compatible annotation encoding. The
vehicle and timetable speed limit code is unchanged. `MixinCompatibilityTest`
checks every configured mixin, preserves the separate array encoding required
by `@Inject`, and verifies that both speed redirects still target calls present
in the bundled Core. `:fabric:verifyMixinCompatibility` runs those checks against
the actual remapped distribution JAR and is included in `:fabric:check`.

```text
gradlew :fabric:verifyMixinCompatibility
```

When updating the Loader dependency, retain compatibility with supported runtime
versions and rerun this artifact check. A successful Java compilation alone does
not verify Mixin application at game startup.

Validation on 2026-09-06 reproduced the old JAR's exact `ArrayList` to
`AnnotationNode` failure using Fabric Knot with Loader 0.18.4, Mixin
0.17.0+mixin.0.8.7, and MixinExtras 0.5.0. Under the same runtime, the fixed JAR
successfully applied both speed redirects, the vehicle speed extension, and the
path accessor. This smoke check loads transformed Core classes without starting
the Minecraft server or creating a world; it is not a full gameplay test.
The same fixed JAR also passed the smoke check with Loader 0.19.5 and its Mixin
0.17.4+mixin.0.8.7 / MixinExtras 0.5.5 runtime.
All 45 selected regression tests, the three distribution JAR checks, and Fabric
and Forge compilation passed. Comparing ZIP entry contents found changes only
in the two speed mixin classes and the build manifest; the bundled Core and
Mappings JARs were unchanged.

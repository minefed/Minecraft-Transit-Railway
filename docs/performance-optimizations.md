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

# Vehicle shadow-pass checks

`RenderVehicles.render` now evaluates the existing
`OptimizedRenderer.renderingShadows()` once per invocation and shares that local
boolean between culling-task collection and the per-car sound guard. Previously,
each visible car repeated the same stack-trace scan before playing sounds.

The pinned Mappings implementation checks the current stack for the class-name
prefixes `net.irisshaders` and `net.optifine`. The vehicle loop, indexed car loop,
and `CustomResourceLoader.getVehicleById` callback all run synchronously. Their
additional frames belong to MTR, its shaded collection library, or the JDK;
`getVehicleById` performs a map lookup and immediately invokes the consumer.
Therefore all cars reached in this invocation have the same shadow-pass result.
Multiple vehicles and cars still execute their original rendering and sound
conditions; only repeated queries are removed.

The value is local, with no static or thread-local cache. Every subsequent
invocation, including a shadow pass after an ordinary pass or the reverse,
queries the original implementation again. Shader presence, shader toggles,
and the original prefix semantics remain delegated to Mappings. Deferred rail
callbacks keep their own shadow checks. This uses no new Java API or mixin;
Forge's existing source-copy task picks up the common change, but Forge has not
been separately built or tested for this change.

Two earlier 30-second device JFR captures contained 72/1,180 and 172/2,205 render
thread allocation samples at the per-car shadow-check call site. These are
unweighted sample counts, not bytes, CPU percentages, or predicted FPS gains.
The removed calls construct an exception/stack snapshot and stack-trace elements
in the pinned Java 17 implementation. No new in-game FPS result is claimed.

Validation uses the existing Java 17 test suite, rail/mixin/light compatibility
checks, and final remapped JAR. Independent bytecode inspection verifies one
remaining shadow query in `render`, capture of its result through the synchronous
callbacks, and the captured boolean guarding both motor and door sounds.

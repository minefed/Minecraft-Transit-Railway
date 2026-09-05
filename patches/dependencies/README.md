# Dependency source history

The mod tracks `libs/Transport-Simulation-Core-0.0.1.jar` and
`libs/Minecraft-Mappings-common-0.0.1.jar`. Both performance replacements were
committed in `48e7a697`. The `*.jar` ignore rule does not untrack existing files.
The additional mod optimizations in `c577c173` do not change either JAR.

The corresponding dependency source edits previously existed only in temporary
checkouts outside this repository. This directory preserves their source,
tests, and build changes as of 2026-09-06. The changes originated in the task
`01a062ae-8fc4-7b22-81f9-6f932046f3a4` (2026-09-03). Temporary directories and
task history are no longer required to recover these source edits.

## Recorded snapshots

| Dependency | Upstream base commit | Patch contents |
| --- | --- | --- |
| Transport-Simulation-Core | `ecde7247f4200a417ff19dd257276f40e666d11b` | 31 production sources, `build.gradle`, one new regression test |
| Minecraft-Mappings | `a0567ff7219a7f44b06089d208ab866e9a19e029` | Two packet sources, two existing build files, standalone build/settings, one new regression test |

`provenance.json` records upstream URLs, base commits, source tree IDs, patch
SHA-256 hashes, current mod JAR SHA-256 hashes, and every changed file. The tree
IDs identify the complete patched Git trees, not published commits. Each patch
was applied to a separate index at its recorded base; the resulting tree matched
the captured source snapshot exactly. The original checkouts and their indexes
were left unchanged. Generated sources, build output, and caches are excluded.

Both upstream projects use the MIT license; their identical notice is preserved
in `UPSTREAM-LICENSE.txt`.

## Restore source for development

Use new checkout directories outside a temporary folder. For example, run these
PowerShell commands from the mod repository root; the destination names must not
already exist. `$patchDirectory` is an absolute path so `git -C` remains safe.

```powershell
$patchDirectory = (Resolve-Path 'patches/dependencies').Path
git clone https://github.com/Minecraft-Transit-Railway/Transport-Simulation-Core.git ../Transport-Simulation-Core
git -C ../Transport-Simulation-Core switch --detach ecde7247f4200a417ff19dd257276f40e666d11b
git -C ../Transport-Simulation-Core apply --check "$patchDirectory/transport-simulation-core.patch"
git -C ../Transport-Simulation-Core apply --index "$patchDirectory/transport-simulation-core.patch"
git -C ../Transport-Simulation-Core write-tree

git clone https://github.com/Minecraft-Transit-Railway/Minecraft-Mappings.git ../Minecraft-Mappings
git -C ../Minecraft-Mappings switch --detach a0567ff7219a7f44b06089d208ab866e9a19e029
git -C ../Minecraft-Mappings apply --check "$patchDirectory/minecraft-mappings.patch"
git -C ../Minecraft-Mappings apply --index "$patchDirectory/minecraft-mappings.patch"
git -C ../Minecraft-Mappings write-tree
```

Stop if any command fails. Compare each `write-tree` result with
`sourceSnapshotTree` in the manifest, then create a development branch and commit
the staged source edits in that dependency repository. These are working-tree
patches for `git apply`, not mail patches for `git am`.

## Build provenance and limits

This archive guarantees source recovery. It is not yet a hermetic build or proof
that a fresh environment produces byte-identical JARs. The archival step did not
rebuild or replace the mod's JARs.

- The previous Core checkout's `build/libs/Transport-Simulation-Core-0.0.1-relocated.jar`
  matches the committed Core JAR byte for byte. Its build uses Java 21 with
  `--release 8` and Shadow relocation of Gson/fastutil names. Schema sources must
  be generated with `generateJavaSchemaClasses` before compilation on a fresh
  checkout. The patched build still contains dynamic dependency versions, and
  schema/web assets and the build dependencies must also be available.
- Core's final compatibility build substituted the exact bundled fastutil API
  for the Maven dependency. The original helper project and init script are
  archived under `core-linkage-validation/`. They reverse-relocate fastutil from
  the mod's `libs/Shadow-Libraries-util-0.0.1.jar`, then use that output as Core's
  compile dependency. They use the original container paths `/repo` (mod root)
  and `/validation` (helper root). Run `reverseRelocateFastutil` in the helper
  project before building Core with
  `-I /validation/use-shipped-fastutil.init.gradle`; preserve those mounts or
  adapt the paths. Core's distribution task is `shadowJar`. A regular Maven
  fastutil build alone does not establish compatibility with the shipped library.
- The two archived Mapping packet classes match the two class entries in the
  committed common JAR byte for byte when compared with the old checkout's build
  output. The complete standalone Mapping JAR was not available for comparison.
  Its isolated test/build entry point is Gradle 8.14 from `common`, with
  `--settings-file settings-standalone.gradle test jar`; Java 21 emits Java 8
  bytecode. Preserve the other entries of the bundled JAR until a complete
  replacement has passed API, resource, and runtime compatibility checks.

Before publishing future rebuilt artifacts, pin dependency versions and the JDK,
preserve required generated resources, test against the mod's shipped libraries,
and run the mod regression and game compatibility checks. Record the new source
commit and artifact hash together. A matching hash of the archived JAR identifies
the existing binary; it does not substitute for a reproducible build recipe.

## Ongoing maintenance

Use dedicated Core and Mappings forks as the long-term source of truth. Commit
source changes there, and pin their exact commits here with submodules or a
dependency manifest. Submodules pin source revisions; the build must still
explicitly consume their output. Give custom artifacts unique versions rather
than silently replacing a shared `0.0.1` release, and record artifact SHA-256
hashes with the source revisions. Keep the currently tracked JARs until that
build/distribution workflow has been migrated and verified.

Until those forks and builds are in place, update the patches and manifest in
the same mod commit as each JAR replacement, including new source and test files.
Verify patch replay against the pinned bases every time. Do not depend on edits
in a temporary checkout or on a task transcript. Git LFS can reduce binary
storage costs, but does not preserve the source history or build provenance.

References: [tracked files and gitignore](https://git-scm.com/docs/gitignore),
[submodule revision tracking](https://git-scm.com/docs/gitsubmodules),
[applying source patches](https://git-scm.com/docs/git-apply).

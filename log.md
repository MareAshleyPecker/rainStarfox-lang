# Native line work log

Working log for the `feat/native-allocator-ctx` branch. Written in English so an
automated agent reading this repository can reconstruct what was done and what
is still open, without reading the chat history.

Status: **first slice landed and verified on the C side; the Kotlin side compiles
but the Gradle build was not run here** (see "Environment" below).

## Scope of this branch

Replace the C backend's memory model. Before this branch the runtime did:

```c
static bool rsf_err = false;          /* global mutable error flag   */
static void** rsf_alloc_reg;          /* global allocation registry  */
/* every malloc registered, everything free()d from atexit */
```

Problems: not reentrant, not thread-safe, no way to hand ownership to a host,
and `realloc` tracking was O(n) per call.

New model is "Zig's implementation, Odin's shape":

- `rsf_allocator` — `{alloc, resize, free, user}`, an allocator is a *value*, not
  a global.
- `rsf_ctx` — holds `alloc` plus `err` / `err_msg`. The generated code still
  writes `rsf_err` / `rsf_err_msg`; both are now macros expanding to
  `rsf_ctx_get()->...`, so **no code emission changed**.
- Default allocator is a process-wide arena: 64 KiB blocks, bump allocation,
  one `reset` at exit.
- Growth uses `rsf_grow(ptr, old_size, new_size)`. Types are static, so every
  call site knows the old size — no allocation headers. All 3 `realloc` sites
  were converted.
- `rsf_ctx_init` / `rsf_ctx_use` let a host (`--lib`, embedding) own the context;
  the runtime allocates nothing on its own.
- TLS: `_Thread_local` / `__thread`, `-DRSF_NO_TLS` falls back to a plain global.
- `runtimeLibSource()` grew a guard: it throws if a raw `malloc`/`calloc` /
  `realloc`/`free` survives the transform in the runtime body.

## Commits

| commit | what |
|---|---|
| `ac0156f` | Baseline snapshot of the **uncommitted** work that shipped in the tarball (runtime split into a header). Committed as-is so the next commit is reviewable. |
| `8ee2c71` | The memory model change described above. |
| (this one) | Header rename + fix for a half-finished wiring (below) + this log. |

## Bug found in the shipped working tree

The tarball's uncommitted change had two halves and only one was done:

1. `CBackend.emit()` was switched to `#include "rsfRuntimelib.h"`.
2. **But nothing ever wrote that header**, and `emitImpl` was never passed as
   `true`. `Main.kt` still called `CBackend(model, withEntry = !cLib).emit()`.

Consequence: `rsf build --c` produced a `.c` that could not compile at all
(missing header), and even with a header copied in by hand it failed to link
(`undefined reference to rsf_alloc_reg_len`) because no translation unit
defined `RSF_RUNTIME_IMPL`.

Fixed in this commit: `Main.kt` now calls `ensureRuntimeLib(outDir)` and passes
`emitImpl = true`. `ensureRuntimeLib` also changed from "skip if the file
exists" to "rewrite if the content differs", so a stale header in `out/`
cannot silently mismatch the compiler.

## Header naming

The header was referenced under three spellings: `rsfRuntimeLib.h`,
`rsfRuntimelib.h` and the checked-in `runtimelib.h/rsfRuntimeLib(2).h`. Two of
them differ only by letter case, which collides on Windows / macOS
case-insensitive filesystems (the dev machine is Windows + mingw64).

Renamed to **`rsf_runtime.h`** everywhere: lowercase-with-underscore matches the
`rsf_` symbol prefix and leaves room for a future real `rsf_runtime.c`.

## Verification

Environment: Ubuntu 24.04 aarch64 under proot on Android. JDK 17
(`/usr/lib/jvm/java-17-openjdk-arm64`) — package `postinst` fails on
`update-alternatives`, so `mkdir -p /usr/share/man/man1 && dpkg --configure -a`
fixes it. No `javac`; Kotlin was compiled directly:

```sh
# compiler + deps taken from the Gradle cache, ASM/stdlib from the fat jar
CP=$(find ~/.gradle/caches/modules-2/files-2.1/{org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.10,...} -name '*.jar' | tr '\n' ':')
java -Xmx1500m -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath dist/rainStarfox-0.9.0.jar \
  -jvm-target 17 -d out-check src/main/kotlin
# -> 272 classes, 0 errors
```

The Gradle build itself was **not** run: `build.gradle` has
`kotlin { jvmToolchain(25) }` and only JDK 17 is available here.

C runtime, no JDK needed — `build/verify69/run.sh` regenerates the header from
`CBackend.kt` and compiles a standalone test:

```sh
sh build/verify69/run.sh
# c11 / c99 / no-TLS / gnu17 -O2, all -Wall -Wextra -Werror -> ok
```

It covers: arena allocation across multiple blocks (20k allocations retained),
`rsf_calloc` zeroing, `rsf_grow` with explicit old size, error state living in
the context, cross-TU sharing of `rsf_ctx_cur`, and a host-injected allocator via
`rsf_ctx_init` / `rsf_ctx_use`.

End-to-end, `--c` output compared against the previous compiler build and the
interpreter:

| example | result |
|---|---|
| `rsfSrc/lang/class.rsf` | generated C compiles; output **identical** to both the pre-change C build and `rsf run` |
| `rsfSrc/lang/errors.rsf` | output identical to the pre-change C build (both stop after section (a) — pre-existing C backend gap, not a regression) |

ASan could not run in this proot environment (`sanitizer_allocator_primary64`
fails to initialise), so only functional checks were done here.

## Known open items

- `defer` / `errdefer` / `destroy` and scoped arenas are **not** implemented —
  that is the next slice. Arena `free` is currently a no-op, which is the
  documented contract, not a bug.
- The C backend still rejects most examples (multi-dimensional arrays, doubles
  in string concat, JDK types, inheritance, generics). Those are listed by
  `rsf build --c` as explicit errors.
- `errors.rsf` truncates after the first section under `--c`; identical before
  and after this branch, worth its own investigation.
- `runtimelib.h/` in the repo root is an untracked, stale copy of an older
  generated header. Nothing reads it; it can be deleted.

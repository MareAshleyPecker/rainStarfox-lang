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

---

## Extension functions — syntax landed, semantics is the next slice

Done (`e54ed73`): `fn Int.twice() -> Int` / `fn Foo.bar()` parse into
`FuncDecl.receiverType`; the Checker stops with an explicit
"扩展函数还没接语义" error. Existing corpus is unaffected (9/9 parity).

Next slice, in this order:

1. **Desugar to a leading parameter.** In `Parser.parseFuncDecl`, when
   `receiverType != null`, prepend `Param("this", receiverType)` to `params`.
   Keep `receiverType` on the node — the LSP and the "no private access" rule
   both need to know it is an extension.
2. **Bind `this`.** `Checker.checkFunctionBody`: if `fn.receiverType != null`,
   register a binding for the name `this` pointing at param slot 0, so
   `ThisExpr` in the body resolves. Today `this` only resolves inside classes.
3. **Fallback lookup.** In the member-call resolution (around
   `Checker.kt:6534` / `6607`), when normal member lookup fails and the
   receiver's static type matches an extension's `receiverType`, record the
   target in a new `SemanticModel.extensionCalls[expr] = decl` and type-check
   the arguments against `decl.params.drop(1)`.
4. **Interpreter** (`interp/Interpreter.kt`, the `evalMember` call path): if
   `extensionCalls[expr]` is set, evaluate the receiver + args and call the
   function with the receiver first.
5. **Bytecode + C backends**: same shape — the call site becomes a static call
   with the receiver prepended. No ABI change; these are already `public static`
   functions. Until this lands, both backends should *explicitly refuse* an
   extension call rather than miscompile it.
6. **LSP** (`lsp/RsfSymbols.kt`, `lsp/EditorSupport.kt`):
   - index extensions under the receiver type (document symbol group
     "Extensions");
   - completion after `x.` lists extensions of `x`'s static type;
   - hover / definition jump to the `fn Foo.bar` line.
7. Rules copied from Kotlin: extensions cannot touch private members; dispatch
   is static (by the receiver's declared type, not its runtime type).

## Bootstrap line prep (direct machine code)

When `.rsfs` is done, the next track is self-hosting with direct codegen.
Two things from this session matter there:

- The C backend now has a real error model (`setjmp`/`longjmp` handler stack in
  `rsf_ctx`), which is what lets the self-hosted interpreter run natively.
- A native, JVM-free runner already exists end to end:
  `rsf build rsfSrc/bootstrap/InterpCli.rsf --c` + `gcc -lm` produces
  `interp`, and it matches the Kotlin interpreter byte-for-byte on all nine
  `rsfSrc/lang` examples (`tools/dev/quick.sh`, parity 9/9, ~10 ms startup vs
  ~400 ms on the JVM).

For direct machine code, keep the export story simple: emit object files and let
the platform linker build `.so`/`.dll` (export tables are the linker's job);
`export fn` decides visibility and `rsf_` prefixes the symbol. Only write PE
`.edata` / ELF `.dynsym` by hand if the single-file, no-toolchain goal becomes
real.

## rsf build — four phases are wired (stage 83)

`rsf build` in a project directory now runs the four phases from `HANDOFF.md` §8.
What actually exists:

- `build/BuildPlan.kt` — `TargetKind {exe, lib, jar, copy}`, `TargetSpec`,
  and `BuildPlan`: declaration order is build order; `need(a, b)` is a stable
  topological edge (accepts a target name *or* a source path); duplicate names
  and cycles fail at declaration/ordering time with both origins listed.
- `build/BuildHost.kt` — `exec` / `target` / `need` on the host interface.
  `exec` runs `/bin/sh -c` with the project root as cwd and `inheritIO`, so gcc
  output is visible live and the exit code goes back to the script.
- `sema/Builtins.kt` — `Builtin.buildOnly`, used by `exec`/`target`/`need`
  (and retro-fitted onto the older `outDir`/`compile`/`compileAll`).
- `directives/Directives.kt` — `#target { @/a.rsf -> exe }`, optional third
  `-> out` segment; only allowed in the root `build.rsfs`, same as `#build`.
- `Main.kt` — phase 0 seeds the plan from `#target`, phase 2 interprets
  `build.rsfs` with the host, phase 3 walks `plan.ordered()` and emits
  exe (`--c --link`), lib (`gcc -c`), jar (JVM backend + `writeJar`), copy.
  `emitNative` / `linkExe` / `compileObject` / `buildJar` were extracted from
  the single-file path so `rsf build x.rsf --c --link` and the target table
  share one implementation.

Two deliberate deviations from the written design, both documented in §8:

- `exec` returns `Int`, not `!Int`. A non-zero exit is a *normal* value the
  build script branches on; only "the command could not start" aborts.
- `kind` is a string in `target(src, kind)`. A bare identifier is not a value
  in rsf; the `#target` block has no type system, so it keeps the bare `exe`.

Enforcement has three layers: Checker (file must be named `build.rsfs`),
interpreter (no host → clear failure), and both codegen backends (explicit
refusal, since a compiled artifact has no compiler to call).

`build/verify84/run.sh` is the reproducible judge: a small project with
`exe` + `lib` + `copy` (explicit output path), `need` reversing the default
order, and an `exec` whose exit code the script checks.

Not done yet: phase 1 (`#dep rule("f")` calling a build function) — it needs a
"call this top-level function by name" entry point on the interpreter, and
today `rsf build` merely prints a warning that rules are not executed. Also
open: `compile()`/`compileAll()` (stage 81) now coexist with the target table
and their relationship needs to be settled in the docs — `compile` compiles
immediately, `target` only records intent.

## Build rules — `#def` replaced the string-prefix protocol (stage 83, phase 1)

The design said rules are functions that return strings with `c:/h:/lib:/jar:/dir:`
prefixes, and `#dep rule("f")` names one. That was a stringly-typed escape hatch
and it also needed an interpreter entry to "call this top-level function by
name". Replaced by a declaration, at the user's suggestion:

```rsf
#def genDeps {
    exec("protoc --out=gen proto/x.proto")
}
#dep { rule("genDeps") }
```

Mechanism: `#def 名字 { … }` is rewritten **at load time** into
`fn 名字() -> void { … }` — only the header token is replaced, everything from
`{` to the closing `}` is kept byte-for-byte. So body lines and columns are
untouched and errors inside a rule body point at the line the user wrote
(verified: `println(nope)` reports line 3, col 13, the original position).
Newlines are preserved, so nothing after the block drifts either.

Consequences that fell out for free:

- rules are ordinary functions — callable from `main`, from each other, and from
  an imported module (`build/rules.rsfs`), matching "rules can be referenced
  from other scripts";
- duplicate `#def foo` in two files is caught by the existing checker as a
  duplicate function declaration — no second duplicate-detection path;
- `#dep rule("x")` is validated **before anything runs**: unknown names report
  the known rules and the available zero-arg functions.

`runEntry(rules)` runs the rules and then `main` inside the **same interpreter
instance**, so globals initialise once. Two `Interpreter` objects would have run
`const val X = readText(...)` twice.

`build/verify84` now includes a rule module and asserts the rule prints before
`main` and that its `exec` ran.

Next slice: the declaration builtins `java/c/h/dir` for rule bodies, then jar on
the analyse classpath (which is what "build scripts can call jars" actually
needs), then `need` lines inside `#target`, then settling `compile`/`compileAll`
versus the target table.

## Rule bodies can declare deps — and `c` / `h` stay legal names elsewhere

Step 2 of the build track: `javaInclude(...)`, `cInclude(...)`, `hInclude(...)`, `dirInclude(...)` are now
builtins, `Builtin.buildOnly`, available in build scripts. They append to the
same `DepEntry` set that `#dep { … }` blocks feed, so the lock file and the
existence check have exactly one implementation. `dir` is a new `DepKind`:
validated with `isDirectory`, stored without a hash (hashing a tree in v1 is not
worth it).

The interesting part is the name scoping. `c` and `h` are one-letter names, and
the checker's rule "a user function may not shadow a builtin" is global — adding
those builtins would have made `fn c(x : Int)` illegal in *every* program. So the
reservation is narrowed to `build.rsfs`, which takes **three** guards, because
letter-by-letter each one hid a different failure:

1. declaration: `BUILTINS_BUILD_ONLY` names are only reserved when
   `currentSource.name == build.rsfs`;
2. call resolution in the checker: if a user function of that name exists, bind
   to it instead of the builtin — without this, `fn c` declared fine but
   `c(41)` was resolved as the builtin and produced a "build scripts only" error
   at the call site;
3. the three dispatch points (interpreter, bytecode, C): skip the builtin branch
   when `model.callTargets[expr]` says the call is already bound to a user
   function — without this, `fn c` ran through the interpreter and survived
   compilation, then died at runtime inside the builtin.

Verified on all three engines: a program with `fn c(x : Int)` prints `42` via
`rsf run`, via the class file, and via `--c --link`. `build/verify84` gained the
four declarations and asserts all of them land in `.rsfdeps/lock.json`.

## Jars on the analyse classpath, and the editor gets them too

`ClassPath` used `ClassLoader.getSystemClassLoader()` for both resource reads and
the interpreter's reflective calls. Now it has a replaceable loader:
`installLoader(...)` also **clears the cache** — the cache stores `null` for
"not found", so a jar added later would otherwise need a process restart.

Two consumers, one loader:

- the build command installs the jars from `#dep { javaInclude(...) }` **before**
  analysing `build.rsfs`, so the script itself can `import demo.Util`;
- jars declared *inside a rule* take effect immediately for later `compile()`
  calls, but not for the build script that was already analysed (a real, and
  unavoidable, ordering difference between static directives and runtime
  declarations).

The interpreter had twelve `Class.forName(...)` sites; all now go through
`ClassPath.loadClass`. Without that the jar classes stayed invisible at runtime
even though the checker could read their signatures — that was the actual test
failure, not a hypothetical.

Reused for the editor: `build/ProjectJars.kt` walks up from the edited file to the
nearest `build.rsfs`, installs its jars, and keys the install on a fingerprint so
a keystroke does not clear the classpath cache. It only ever reads `.class`
bytes — never initialises a class, because initialising a project's jar inside
the language server is not something an editor should do. Hooked into the four
`Rsf.load` sites of `EditorSupport` and `LanguageServer`; `rsf complete` after
`Util.` now lists `version` from the jar.

Renames in the same slice, at the user's suggestion: `java` / `c` / `h` / `dir`
became `javaInclude` / `cInclude` / `hInclude` / `dirInclude`. The old names
were why "a user function named `c`" needed three separate guards; the longer
names make that collision essentially impossible (the guards stay, but nothing
depends on them any more). All four are variadic, and a directory argument
expands to the matching files (`*.jar` / `*.c` / `*.h`); `dirInclude` stays a
directory because that is what it means.

## `import ` completion now lists classes from dependency jars

`EditorSupport.importedClasses()` already resolved `import demo.Util` through
`ClassPath`, so member completion after `Util.` worked as soon as the jar was
installed. What was missing was the *import line itself*: typing `import ` offered
keywords and builtins, never the classes you just added a jar for.

`Completer.importItems()` recognises `^\s*import\s+([\w.]*)$`, and
`ProjectJars.installedClassNames()` reads the jar entries (`demo/Util.class` ->
`demo.Util`), dropping inner classes (`$`), `module-info`/`package-info` and
`META-INF`, capped at 300. No dependency jars in the project -> returns null and
the ordinary candidate list is used, so nothing changes for projects without jars.

## Calling C from rsf — `extern fn`, and `.c` / `.h` through `import`

The last hole in the build line was that `cInclude` / `hInclude` declared things
nobody consumed: gcc never saw the `.c` files, `-I` never appeared, and rsf had
no way to declare an external C function in the first place.

Now:

```rsf
import "native/util.c"
import "native/util.h"

extern fn util_add(a : Int, b : Int) -> Int
```

`extern` is a top-level function modifier, same slot as `export`. It parses to
"no body + `isExtern`"; the return clause is mandatory (same reasoning as abstract
methods: otherwise "void" and "forgot to write" are indistinguishable), and `!T`
is rejected at declaration time because the C side has no longjmp channel.

C backend: the prototype uses the **C name** (no `rsf_fn_` prefix), the call site
calls it by that name, and no definition is emitted. Bytecode and the interpreter
reject extern explicitly — on the JVM the symbol simply does not exist.

`import` routes `.c` / `.h` into the dependency list in the loader; no lexer or
parser change was needed for that half. The path base had a real bug: dependency
paths were made relative to the load root while the build command resolves them
against the current directory, so a single-file build silently dropped the `.c`
(it stayed in the list as `../native/util.c`, which resolved to the wrong place).
Both sides now use "project root if a build.rsfs exists, otherwise the current
directory".

Known gap, recorded in HANDOFF §9: the bootstrap lexer/parser do not know
`extern` yet (they know no directives at all), so sources using it are
Kotlin-only for now.

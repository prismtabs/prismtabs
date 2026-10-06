# PrismTabs

Read `docs/tab-groups-plugin-implementation-plan.md` before making design decisions. Items marked
**[VERIFY]** must be checked against the platform bytecode (recipe below) before writing code against
them — never guess a signature. The constraints in README §"Constraints that are not going away" are
platform limits, not TODOs; do not design around them.

## Verifying a platform API

There is **no** platform sources jar on this machine — only the extracted IDE. Inspect signatures with
`javap`, and build the classpath from *all* jars: classes are spread across `lib/`, `lib/modules/` and
`plugins/`, so a single-jar classpath will falsely report "class not found".

```bash
IDE=$(find ~/.gradle/caches/*/transforms -maxdepth 6 -name 'ideaIC-*' -type d | head -1)
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';'; CONV='cygpath -m -f -';; *) SEP=':'; CONV='cat';; esac
CP=$(find "$IDE" -name '*.jar' | sed 's|/[^/]*$||' | sort -u | $CONV | sed 's|$|/*|' | tr '\n' "$SEP")
javap -cp "$CP" com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
```

Three details in that recipe are load-bearing on Windows, and all three fail *silently or misleadingly*
— as "class not found", the very symptom the paragraph above warns about:

- **`-maxdepth 6`, not 3.** The Windows transform puts the IDE four levels below `transforms/`
  (`<hash>/workspace/transformed/ideaIC-2024.2-win`), so a shallower search sets `IDE` to the empty
  string and every lookup fails.
- **The classpath lists *directories* with a trailing `/*`, not the jars themselves.** Spelled out,
  this IDE's 394 jars are 66 KB of command line — past Windows' ~32 KB limit, so `javap` never runs
  ("Argument list too long"), and it takes no `@argfile` to get around it. The 102 directories that
  hold them are 15 KB, and Java expands `dir/*` to the same jars. Derive that list from the recursive
  `find` rather than hardcoding `lib`, `lib/modules` and `plugins/*/lib`: 28 further directories hold
  jars, including a top-level `modules/`. `sed` strips the filename rather than `find -printf '%h\n'`,
  which is GNU-only and would break the recipe on the macOS box the UI suite also targets.
- **`;` and `C:/…` paths off Unix.** `javap` here is the Windows JDK's, so it wants a `;` separator
  and cannot read a Git Bash `/c/…` path. `cygpath -m` gives the mixed form, whose forward slashes
  need no further escaping.

Verified end to end on Windows 11 (`FileEditorManagerEx`, `DynamicPluginListener`, `IconLoader` all
resolve); the counts above are this machine's `ideaIC-2024.2-win`.

## Calling a platform Kotlin API

Never omit a defaulted argument on a `com.intellij` function or constructor. Kotlin compiles the
omission into a call to a *synthetic* member (`foo$default`, or a constructor taking every parameter
plus a `DefaultConstructorMarker`) whose descriptor changes whenever the platform adds a parameter —
so it links fine against the compile baseline and throws `NoSuchMethodError` on the user's newer IDE.
Pass every argument explicitly, or build the value in Java, where the zero-argument constructor
Kotlin generates but refuses to call is reachable. `PlatformDefaultArgumentCallsTest` fails on a new
one; README §"Never omit a default argument on a platform Kotlin API" has the full account.

## Internal API

Marketplace refuses an upload that uses `@ApiStatus.Internal` platform members, and this plugin
now uses **none** — `acceptedInternalApiUsages` in `build.gradle.kts` is empty and must stay that
way. Two things enforce it: `INTERNAL_API_USAGES` is in `verifyPlugin`'s `failureLevel`, and
`verifyInternalApiAllowlist` finalizes `verifyPlugin`, names any offender, and also fails if that
level is ever dropped again.

Getting a build green by adding an entry is the wrong move. Read README §"Internal API: what the
Marketplace asked for" first — it records how each of 1.4.14's three refused calls was replaced.

**Before concluding the platform has no public equivalent, dump the whole type and read all of it.**
The repaint was called impossible for two rounds, and a Marketplace appeal drafted over it, because
`javap` output for `FileEditorManager` was piped through `head -40`; the method that does the job,
`updateFilePresentation`, sits below that cut. Never pipe a listing through `head` and then reason
from its absence. Two further rules that matter here: a class in an `impl` package is *not*
automatically off-limits — check the annotation, since `EditorWindow`, `EditorComposite` and
`TabInfo` carry none — while a Kotlin-`internal` member (mangled, or refused by the Kotlin
compiler) is off-limits whatever the Verifier says.

## Build

`./gradlew test` (fast, no IDE) · `./gradlew runIde` (sandbox IDE). Toolchain is JDK 21; the shell's
default `java` is 25, which is fine — Gradle picks 21 from `~/.jdks` itself.

The shipped jar and `runIde` are obfuscated by ProGuard. A class newly named in `plugin.xml`, or newly
persisted, needs a keep rule in `proguard-rules.pro` (README §"Obfuscation");
`./gradlew obfuscationTest` fails until it has one.

# PrismTabs

Never guess a platform signature: check it against the platform bytecode (recipe below) before writing
code against it.

## Verifying a platform API

There is **no** platform sources jar on this machine — only the extracted IDE. Inspect signatures with
`javap`, and build the classpath from *all* jars: classes are spread across `lib/`, `lib/modules/` and
`plugins/`, so a single-jar classpath falsely reports "class not found". Dump the whole type and read
all of it; never pipe a listing through `head` and reason from a method's absence.

```bash
IDE=$(find ~/.gradle/caches/*/transforms -maxdepth 6 -name 'ideaIC-*' -type d | head -1)
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';'; CONV='cygpath -m -f -';; *) SEP=':'; CONV='cat';; esac
# List jar directories (dir/*), not jars: the full jar list exceeds the Windows command-line limit.
# sed, not GNU-only `find -printf`, strips the filename.
CP=$(find "$IDE" -name '*.jar' | sed 's|/[^/]*$||' | sort -u | $CONV | sed 's|$|/*|' | tr '\n' "$SEP")
javap -cp "$CP" com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
```

On Windows the IDE sits four levels below `transforms/`, hence `-maxdepth 6`. `javap` there is the
Windows JDK's: it needs `;` as the separator and mixed `C:/…` paths (`cygpath -m`), not Git Bash `/c/…`.
A wrong value in any of these fails as "class not found".

## Calling a platform Kotlin API

Never omit a defaulted argument on a `com.intellij` function or constructor. The omission compiles to a
synthetic member (`foo$default`, or a constructor with a `DefaultConstructorMarker`) whose descriptor
changes when the platform adds a parameter, so it throws `NoSuchMethodError` on a newer IDE. Pass every
argument explicitly, or build the value in Java. `PlatformDefaultArgumentCallsTest` fails on a new one.

## Internal API

Marketplace refuses an upload that uses `@ApiStatus.Internal` platform members. The plugin uses none:
`acceptedInternalApiUsages` in `build.gradle.kts` stays empty, and the build fails on any entry. Do not
add one to get a build green.

Before concluding the platform has no public equivalent, check these:

- A class in an `impl` package is not automatically off-limits. Check the annotation: `EditorWindow`,
  `EditorComposite` and `TabInfo` carry none.
- A Kotlin-`internal` member (mangled, or refused by the Kotlin compiler) is off-limits whatever the
  Verifier says.

## Persisted state

State classes keep their class and property names: renaming one loses users' saved groups or palette.
`SavedStateTest` fails on a rename.

## Build

`./gradlew test` (fast, no IDE) · `./gradlew runIde` (sandbox IDE). Toolchain is JDK 21.

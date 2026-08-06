# Chromatabs

Read `docs/tab-groups-plugin-implementation-plan.md` before making design decisions. Items marked
**[VERIFY]** must be checked against the platform bytecode (recipe below) before writing code against
them — never guess a signature. The constraints in README §"Constraints that are not going away" are
platform limits, not TODOs; do not design around them.

## Verifying a platform API

There is **no** platform sources jar on this machine — only the extracted IDE. Inspect signatures with
`javap`, and build the classpath from *all* jars: classes are spread across `lib/`, `lib/modules/` and
`plugins/`, so a single-jar classpath will falsely report "class not found".

```bash
IDE=$(find ~/.gradle/caches/*/transforms -maxdepth 3 -name 'ideaIC-*' -type d | head -1)
javap -cp "$(find "$IDE" -name '*.jar' | tr '\n' ':')" com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
```

## Build

`./gradlew test` (fast, no IDE) · `./gradlew runIde` (sandbox IDE). Toolchain is JDK 21; the shell's
default `java` is 25, which is fine — Gradle picks 21 from `~/.jdks` itself.

# Learning Kotlin and the IntelliJ Platform with PrismTabs

This manual teaches Kotlin and the IntelliJ Platform plugin API using the PrismTabs source as the
worked example. Every snippet is taken from this repository, so after reading a chapter you can open
the file it names and see the same code in context.

It serves two kinds of reader:

- **Learning Kotlin or plugin development.** Read the chapters in order and do the exercises at the
  end of each one.
- **Reading or changing PrismTabs.** Skim chapter 1 for the Kotlin you do not recognise, then go
  straight to chapter 3, which traces each user-visible feature through the code.

| Chapter                                           | What it covers                                                                          |
|---------------------------------------------------|-----------------------------------------------------------------------------------------|
| [1. Kotlin](01-kotlin.md)                         | The language features this codebase uses, from `val` to coroutines, with Java analogies |
| [2. The IntelliJ Platform](02-intellij-platform.md) | How a plugin plugs in: `plugin.xml`, services, actions, listeners, threading, UI, build |
| [3. How PrismTabs works](03-prismtabs-walkthrough.md) | Package map and a trace of every feature, from tab paint to plugin unload            |

## Set up before reading

1. Open the repository in IntelliJ IDEA (see the README's "Build and run"). The IDE resolves every
   platform class, so Ctrl+click (Cmd+click on macOS) on any name in a snippet takes you to it.
2. Run the unit tests once to confirm the toolchain works:

   ```bash
   ./gradlew test
   ```

3. Start a sandbox IDE with the plugin installed:

   ```bash
   ./gradlew runIde
   ```

   Right-click an editor tab and choose **Tab Group…** to see what the code in chapter 3 does.

Keep a Kotlin scratch file open while reading chapter 1 (**File | New | Scratch File | Kotlin**). It
runs any top-level code with the green arrow, which is the fastest way to test a snippet.

## Where the knowledge in this repo lives

| Source                                         | Use it for                                                                |
|------------------------------------------------|---------------------------------------------------------------------------|
| KDoc comments on each class                    | Why the class is shaped the way it is. They are long and deliberate.     |
| `docs/engineering-notes.md`                     | Platform findings, constraints, and the reasoning behind each feature     |
| `docs/tab-groups-plugin-implementation-plan.md` | The design plan and the phase breakdown the KDoc refers to               |
| `docs/naming-and-terminology.md`               | User-facing wording rules                                                 |
| `CLAUDE.md`                                    | How to verify a platform API with `javap`, and the rules that follow from it |

## External references

- Kotlin documentation: <https://kotlinlang.org/docs/home.html>
- Kotlin Koans, short interactive exercises: <https://play.kotlinlang.org/koans/overview>
- IntelliJ Platform Plugin SDK: <https://plugins.jetbrains.com/docs/intellij/welcome.html>

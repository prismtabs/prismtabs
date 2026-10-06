# ProGuard rules for the plugin jar Marketplace ships. build.gradle.kts supplies the jars and the
# mapping file; this file holds only the rules. README "Obfuscation" explains the setup.

# Renaming only. Shrinking would delete whatever the platform reaches by reflection alone and no
# rule below names. Optimisation rewrites method bodies, while the unit and UI test suites run the
# unobfuscated classes, so nothing would test what it changed.
-dontshrink
-dontoptimize

# Renamed classes move into io.prismtabs, so a stack trace or an idea.log category still names the
# plugin: the manual tests attribute a SEVERE entry to PrismTabs by its io.prismtabs frames.
-repackageclasses io.prismtabs

# Signature: the platform reads PersistentStateComponent<T>'s T and each state field's element type
#   from generic signatures.
# InnerClasses, EnclosingMethod: keep Class.getSimpleName and getDeclaringClass answering as they
#   do unobfuscated, for platform code that reflects on these classes.
# *Annotation*: @Service, @State and the xmlb annotations are read at runtime.
# SourceFile, LineNumberTable: line numbers, under a fixed source file name, so ProGuard's retrace
#   can map a user's stack trace back through the release's mapping file.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Every class plugin.xml names. The platform loads each one by that name.
-keep class io.prismtabs.provider.TabGroupColorProvider { <init>(...); }
-keep class io.prismtabs.ui.TabGroupsToolWindowFactory { <init>(...); }
-keep class io.prismtabs.ui.TabGroupPaletteConfigurable { <init>(...); }
-keep class io.prismtabs.listener.PrismTabsDynamicPluginListener { <init>(...); }
-keep class io.prismtabs.listener.TabGroupFileListener { <init>(...); }
-keep class io.prismtabs.listener.TabGroupVfsListener { <init>(...); }
-keep class io.prismtabs.action.AssignTabGroupAction { <init>(...); }

# Persisted state. XmlSerializer writes class and property names into the user's tabGroups.xml and
# prismtabsPalette.xml, so renaming any of them would lose every user's groups and palette on update.
-keep class io.prismtabs.model.TabGroupState, io.prismtabs.model.TabGroupState$* { *; }
-keep class io.prismtabs.model.TabGroupPaletteState, io.prismtabs.model.TabGroupPaletteState$* { *; }

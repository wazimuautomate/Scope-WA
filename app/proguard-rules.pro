# Minification is disabled for the release build type as of 1.0.0 — see the
# comment on `release { }` in app/build.gradle.kts for why. These rules are kept
# accurate so re-enabling R8 is a two-line change plus an on-device test.

# Keep Accessibility Service and Foreground Service classes referenced only from the manifest.
-keep class com.tricreta.scopewa.accessibility.** { *; }
-keep class com.tricreta.scopewa.jobrunner.** { *; }

# Room. room-runtime ships consumer rules that keep the generated `*_Impl`
# classes it loads by name, but the entities and the TypeConverters class are
# only reached through generated code, and enum constants used by converters are
# matched by name — keep both explicitly rather than relying on that.
-keep class com.tricreta.scopewa.data.db.entity.** { *; }
-keep class com.tricreta.scopewa.data.db.Converters { *; }
-keepclassmembers enum com.tricreta.scopewa.** { *; }

# org.json is part of the Android platform; UpdateChecker parses update.json
# reflection-free, so nothing else is needed for the updater.

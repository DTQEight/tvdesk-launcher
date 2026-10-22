# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# ijkplayer 通过 JNI 按类名/方法名注册，不能被 R8 改名或裁剪
-keep class tv.danmaku.ijk.** { *; }
-keepclassmembers class tv.danmaku.ijk.** { *; }
-dontwarn tv.danmaku.ijk.**
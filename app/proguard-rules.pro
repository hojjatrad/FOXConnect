# gomobile/JNI classes are referenced both directly by the native runtime and reflectively.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keepattributes *Annotation*

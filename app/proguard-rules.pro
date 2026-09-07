# gomobile/JNI classes are referenced both directly by the native runtime and reflectively.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
# WorkManager persists and instantiates worker class names.
-keep class com.foxconnect.app.update.UpdateCheckWorker { public <init>(...); }
-keepattributes *Annotation*

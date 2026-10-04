# Shizuku
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# The Shizuku user service is instantiated by the Shizuku server via reflection, so its
# constructors and the generated AIDL Stub must survive shrinking.
-keep class com.droidperf.shizuku.PerfUserService { *; }
-keep class com.droidperf.shizuku.IPerfShell { *; }
-keep class com.droidperf.shizuku.IPerfShell$Stub { *; }
-keepclassmembers class com.droidperf.shizuku.PerfUserService {
    public <init>(...);
}

# libsu
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Keep our AIDL / reflection-light model classes
-keep class com.droidperf.domain.** { *; }

# Kotlin metadata
-keep class kotlin.Metadata { *; }

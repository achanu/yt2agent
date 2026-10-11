# The yt-dlp/python bridge may look up classes reflectively from native code.
-keep class com.yausername.youtubedl_android.** { *; }

# commons-compress (used to unzip the bundled Python env) scans ExtraField
# implementations reflectively in a static initializer.
-keep class org.apache.commons.compress.** { *; }

# XZ is an optional commons-compress dependency; absent at runtime.
-dontwarn org.tukaani.xz.**

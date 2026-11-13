# The Rust library looks these native methods up by name; renaming or removing them would crash
# the release build with UnsatisfiedLinkError.
-keep class linuxboot.runtime.NativeRuntime { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Readable stack traces from release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

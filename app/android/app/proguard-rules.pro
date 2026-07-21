# Vendored Termux:X11 classes are referenced from native code and reflection.
-keep class com.termux.x11.** { *; }

# JNI-bound classes and native method names must survive shrinking/renaming.
-keepclasseswithmembernames class * {
    native <methods>;
}

# AIDL-generated stubs/interfaces.
-keep class com.orailnoor.droiddesk.x11.** { *; }

# Runtime layer touches processes/reflection during setup; keep intact.
-keep class com.orailnoor.droiddesk.runtime.** { *; }

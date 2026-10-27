# LensLink proguard rules.

# The JNI boundary (com.mav.cameranativebridge) must never be renamed:
# camera_bridge.cpp / camera_control.cpp resolve method symbols by name.
-keep class com.mav.cameranativebridge.** { *; }
-keepclassmembers class com.mav.cameranativebridge.** { *; }

# Keep data classes used across the JNI boundary (toString/patterns)
-keepclassmembers @kotlin.jvm.JvmStatic class * { *; }

# JNI registered-by-name entry points keep their attributes
-keepattributes JavascriptInterface,InnerClasses,EnclosingMethod,Signature
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Compose
-dontwarn androidx.compose.**

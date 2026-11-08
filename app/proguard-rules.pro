# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# Application ID: com.smarterai.tech

-keep class com.smarterai.tech.** { *; }
-keep class com.example.domain.models.** { *; }
-keep class com.example.data.local.** { *; }
-keep class com.example.data.network.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

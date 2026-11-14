# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class org.fcitx.fcitx5.android.core.* { *; }
-keep class org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase {
    public <init>(...);
}

# Keep dependency magic
-keep class ** extends org.mechdancer.dependency.Component {
    int hashCode();
    boolean equals(java.lang.Object);
}

# remove kotlin null checks
-processkotlinnullchecks remove

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Keep generic signatures & annotations (Gson/reflective deserialization in dashscope SDK)
-keepattributes Signature,*Annotation*

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# DashScope SDK（反射/Gson 序列化需要保留类名与成员）
-keep class com.alibaba.dashscope.** { *; }
-dontwarn com.alibaba.dashscope.**

# OkHttp & WebSocket（dashscope-sdk-java 传递依赖）
-dontwarn okhttp3.**
-dontwarn okio.**

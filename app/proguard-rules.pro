# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.4.1_1/tools/proguard/proguard-android.txt
# or at the /tools/proguard/proguard-android.txt path in your SDK.

# OkHttp / Retrofit optional dependencies
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Joda Time (transitive)
-dontwarn org.joda.convert.**

# SLF4J
-dontwarn org.slf4j.impl.**

# Google Error Prone Annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.lang.model.element.Modifier
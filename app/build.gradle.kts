import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.alexdremov.notate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alexdremov.notate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(keystoreFile))
                storeFile = file(properties["storeFile"] as String)
                storePassword = properties["storePassword"] as String
                keyAlias = properties["keyAlias"] as String
                keyPassword = properties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Standard debug build uses default ~/.android/debug.keystore
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    lint {
        disable += "MutableCollectionMutableState"
        disable += "AutoboxingStateCreation"
    }

    kotlin {
        jvmToolchain(21)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "6g"
            }
        }
    }

    packaging {
        jniLibs {
            pickFirsts.add("lib/**/libc++_shared.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.6")

    // File System
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Existing View-based dependencies
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.9.0")

    // Color Picker
    implementation("com.github.skydoves:colorpickerview:2.4.0")

    // Onyx SDK
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.0.4")
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // PDF Support
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(kotlin("stdlib-jdk8"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Security & Networking
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")

    // Google Drive
    implementation("com.google.android.gms:play-services-auth:21.5.0")
    implementation("com.google.api-client:google-api-client-android:2.8.1")
    implementation("com.google.apis:google-api-services-drive:v3-rev20251210-2.0.0")

    // Vulnerability force fixes
    constraints {
        // See: https://github.com/alexdremov/notate/security/dependabot/2
        implementation("com.squareup.retrofit2:retrofit") {
            because("Fix XXE vulnerability in Retrofit versions < 2.5.0")
            version {
                strictly("[2.5.0,)")
            }
        }

        // See: https://github.com/alexdremov/notate/security/dependabot/12
        implementation("commons-io:commons-io") {
            because("Fix vulnerability in commons-io versions < 2.14.0")
            version {
                strictly("[2.14.0,)")
            }
        }

        // See: https://github.com/alexdremov/notate/security/dependabot/11
        implementation("com.google.protobuf:protobuf-kotlin") {
            because("Fix vulnerability in com.google.protobuf:protobuf-kotlin < 3.25.5")
            version {
                strictly("[3.25.5,)")
            }
        }
    }
}

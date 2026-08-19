plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.meshnet.meshnet_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.meshnet.meshnet_app"
        // You can update the following values to match your application needs.
        // For more information, see: https://developer.android.com/studio/build/configuration.
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    testOptions {
        // JVM unit testlarida android.* stub'lar (Log, Base64 va h.k.)
        // default qiymat qaytarishi uchun.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            // bouncycastle va jspecify jar'larida bir xil OSGI manifest bor
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // MeshNet: X25519/ChaCha20-Poly1305 kriptografiyasi uchun BouncyCastle
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    // Kotlin korutinlar (async transport ishlari uchun)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Peers/xabarlarni JSON saqlash:
    implementation("com.google.code.gson:gson:2.11.0")

    // JVM unit testlar
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
}

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.meshnet.meshnet_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xno-reflect")
            freeCompilerArgs.add("-Xno-param-assertions")
        }
    }

    defaultConfig {
        applicationId = "com.meshnet.meshnet_app"
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    signingConfigs {
        create("fdroid") {
            storeFile = file("$rootDir/../fdroid/release.keystore")
            storePassword = System.getenv("FDROID_KEYSTORE_PASSWORD") ?: "fdroid"
            keyAlias = "fdroid"
            keyPassword = System.getenv("FDROID_KEY_PASSWORD") ?: "fdroid"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("fdroid")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("long", "BUILD_TIMESTAMP", "0L")
            buildConfigField("String", "BUILD_COMMIT", "\"unknown\"")
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
}
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * Resolves a signing secret from (in order) an environment variable or a local
 * `shahmat-signing.properties` file placed next to the project root. Passwords are
 * deliberately NOT hardcoded here: the properties file is git-ignored, so secrets
 * never enter version control.
 *
 * Env var  ->  shahmat-signing.properties  ->  empty string (signing then fails loudly
 * if the store needs a password).
 */
fun signingSecret(envName: String, propsFile: File, propName: String): String {
    val envValue = System.getenv(envName)
    if (envValue != null && !envValue.isEmpty()) return envValue
    if (propsFile.exists()) {
        val props = Properties()
        val stream = FileInputStream(propsFile.absolutePath)
        props.load(stream)
        stream.close()
        val propValue = props.getProperty(propName)
        if (propValue != null) return propValue
    }
    return ""
}

android {
    namespace = "com.shahmat.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shahmat.game"
        minSdk = 24
        targetSdk = 34
        versionCode = 32
        versionName = "3.2"
        vectorDrawables.useSupportLibrary = true
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../shahmat-release.keystore")
            val secretsFile = file("../shahmat-signing.properties")
            storePassword = signingSecret("SHAHMAT_STORE_PASSWORD", secretsFile, "storePassword")
            keyAlias = "shahmat"
            keyPassword = signingSecret("SHAHMAT_KEY_PASSWORD", secretsFile, "keyPassword")
        }
    }

    // Generate separate APKs per ABI for better BlueStacks compatibility
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
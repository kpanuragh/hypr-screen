import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional: put a keystore.properties (git-ignored) next to settings.gradle.kts.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "com.hyprscreen"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hyprscreen"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    // F-Droid / IzzyOnDroid reject the encrypted dependency-info blob AGP adds to APKs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

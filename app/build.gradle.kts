import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dimts.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dimts.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.2.1"
    }

    signingConfigs {
        create("ignis") {
            storeFile = file("../keystore/ignis-debug.p12")
            storeType = "pkcs12"
            storePassword = "ignis-debug"
            keyAlias = "ignis"
            keyPassword = "ignis-debug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ignis")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ignis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
}

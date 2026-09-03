import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing credentials live outside the repository. Without them a release build is
// still produced, just unsigned — so a checkout by anyone else still compiles.
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.amishutkin.slopsick"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.amishutkin.slopsick"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.1.3"
    }

    signingConfigs {
        if (signing.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Deliberately unminified: the point of this app is that the shipped APK can
            // be read. Obfuscating it would undercut the only claim it makes.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
        }
    }
}

dependencies {
    // Deliberately minimal. Every dependency is one more thing a reader has to trust,
    // and this app's whole claim is that it can be audited in an afternoon.
    testImplementation("junit:junit:4.13.2")
}

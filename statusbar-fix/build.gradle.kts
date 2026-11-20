// LSPosed / Xposed module: makes launchers (e.g. ZTE MiFavor) follow a live wallpaper's
// dark-text hint for the status bar. Needs root + LSPosed; separate from the main app.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.weworkshop.statusbarfix"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.weworkshop.statusbarfix"
        minSdk = 31 // WallpaperColors.getColorHints()
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            // The hook class is loaded by name from assets/xposed_init; don't shrink or rename it.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":xposed-stubs"))
}

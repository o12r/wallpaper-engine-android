// Compile-only stand-ins for the Xposed API (de.robv.android.xposed:api:82). The real classes
// are provided at runtime by LSPosed/Vector, so these are never packaged into an APK. They're
// kept in the repo because the build machine's network can't always reach Maven repositories.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

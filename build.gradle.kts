plugins {
    id("com.android.application") version "9.5.1" apply false
    // JavaSteam 1.8 is built with Kotlin 2.2.x; pin a matching-or-newer KGP for AGP's built-in Kotlin.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

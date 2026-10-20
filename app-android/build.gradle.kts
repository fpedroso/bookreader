plugins {
    id("com.android.application") version "8.6.1" apply false
    // Kotlin 2.4.0, not 2.0.21: this is the minimum that can read litertlm-android's metadata
    // (mv=[2,4,0]). The Compose and serialization plugins track the Kotlin version.
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
}

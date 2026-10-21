plugins {
    id("com.android.application") version "8.6.1" apply false
    // Kotlin 2.4.0. It went there to read litertlm-android's metadata (mv=[2,4,0]) for the local-LLM
    // engines, which have since been removed - but 2.4.0 is kept rather than reverted, because it is
    // what this tree is now built and tested against and there is nothing to gain from the churn.
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
}

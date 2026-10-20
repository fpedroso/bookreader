plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.fpedroso.bookreader"
    compileSdk = 35
    // Pinned: only 35.0.1 / 36.0.0 are installed and cmdline-tools is absent,
    // so AGP cannot auto-download its default 34.0.0 build-tools.
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.fpedroso.bookreader"
        minSdk = 26
        targetSdk = 35
        // Only the two ABIs this app is actually installed on: arm64-v8a for the phone, x86_64 for
        // the emulator. Adding LiteRT-LM put a ~22 MB and a ~26 MB inference library in the APK, and
        // the 32-bit ABIs would carry a third and fourth copy of it (plus another of ML Kit's) for
        // hardware that cannot run the models at a usable speed anyway.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        // Version comes from git rather than being hand-bumped, so it cannot drift: the
        // patch number IS the commit count, meaning every commit yields a distinct build
        // and the number on the home page always identifies what is installed.
        // 0.0.x until the first version is called done; then start the major.
        // CI must check out full history (fetch-depth: 0) or the count would be 1.
        val commits = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 0 }.getOrElse(0)
        versionCode = commits.coerceAtLeast(1)
        // One number everywhere: the same commit count, so "v26" on screen is versionCode 26.
        versionName = "$commits"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // `kotlinOptions.jvmTarget` is an error as of Kotlin 2.4: the compiler options moved to the
    // `kotlin {}` DSL below.
    buildFeatures {
        compose = true
        // For BuildConfig.VERSION_NAME, so the home page can show which build is installed.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // EPUB parsing is hand-rolled: java.util.zip + android's built-in
    // XmlPullParser. Readium was dropped because (a) 3.4.0 requires
    // compileSdk 37 and 3.2.0+ requires 36, neither installed, and (b) it
    // additionally needs core library desugaring. Our fixtures are plain
    // EPUB3, so the spec coverage was not worth the toolchain risk.
    // Upgrade path if an exotic EPUB fails to parse: readium-streamer 3.1.2
    // (minCompileSdk=1) behind the same EpubParser interface.

    // NOTE: Furiganable (ruby text) was evaluated and dropped. Its README lists
    // `onTextLayout` as unsupported - it swaps in an AndroidTextView in some
    // paths - which removes the only way to hit-test a tapped word by text
    // offset. Ui.WordFlow renders each word as its own box instead, which makes
    // tap targets exact and pinyin alignment correct by construction.

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // On-device sentence translation, the comprehension aid for every language - not just
    // the two with bundled dictionaries. Models (~30MB per language pair) are downloaded
    // on demand, but the inference engine ships in the APK as libtranslate_jni.so and is
    // ~16MB PER ABI, which a universal APK multiplies by four. ABI splits are the lever if
    // that ever matters.
    implementation("com.google.mlkit:translate:17.0.3")

    // Language Identification, the last resort for a block whose language nothing declares:
    // 100+ languages, entirely on device. Pre-flighted before use, because Google artifacts
    // are not on Maven Central and this project picks compileSdk/Kotlin pins carefully:
    // 17.0.6 is the newest stable on dl.google.com/dl/android/maven2, the AAR declares no
    // aar-metadata (so no minCompileSdk floor) and carries no Kotlin metadata (Java-only, so
    // Kotlin 2.0.21 is safe), and it adds only language-id-common plus its own ~1 MB native
    // .so per ABI - the play-services/firebase/mlkit-common versions it names are the ones
    // translate above already brings.
    implementation("com.google.mlkit:language-id:17.0.6")

    // Language model inference, for the Qwen3 engines in Settings. One dependency, no NDK: the
    // runtime ships as Kotlin + native .so for arm64-v8a and x86_64. Its metadata is
    // mv=[2,4,0] and its class files are Java 21, which is why the Kotlin pin moved to 2.4.0.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // Unit tests. kxml2 supplies XmlPullParser on the JVM so the production
    // parser can be exercised against the PC-side annotator's output.
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

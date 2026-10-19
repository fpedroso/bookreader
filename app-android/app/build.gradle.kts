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
        versionCode = 1
        versionName = "0.1"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
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

    // Unit tests. kxml2 supplies XmlPullParser on the JVM so the production
    // parser can be exercised against the PC-side annotator's output.
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.ayham.extension"
    defaultConfig {
        // Same as Morphe's music extension; ONNX Runtime needs 24+.
        minSdk = 26
    }
    // JVM unit tests (src/test) for the pure logic; android.util.Log and the like return defaults there.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The extension is plain Java; the Kotlin stdlib the build adds would only duplicate the app's own copy.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    // Spike B: ONNX Runtime's Java API goes into the extension dex; its native libraries are
    // added to the APK by the Spike B patch.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    testImplementation("junit:junit:4.13.2")
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 98
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0.67"

android {
    namespace = "com.mindtravel.reduction"
    compileSdk = 35

    defaultConfig {
        // The installed app's identity. Changing it would make Android treat this as a different app:
        // no update in place, settings and cards left behind.
        applicationId = "com.mindtravel.deepseekassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // Where this build looks for updates; empty, it never looks. Given by whoever builds it (see local.env.example).
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${(project.findProperty("updateManifestUrl") as String?).orEmpty()}\"")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
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

    androidResources {
        noCompress += "onnx"
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
        // The Kotlin API loads only libsherpa-onnx-jni.so (which needs libonnxruntime.so).
        // The C and C++ API libraries in the AAR are never loaded.
        jniLibs.excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }
}

val prepareSpeechAssets by tasks.registering(Exec::class) {
    val script = rootProject.file("tools/models/fetch-models.sh")
    inputs.file(script)
    outputs.file("libs/sherpa-onnx-arm64.aar")
    outputs.dirs("src/main/assets/kws", "src/main/assets/kws-en", "src/main/assets/vad", "src/main/assets/tts-aishell3")
    commandLine("bash", script)
}

tasks.named("preBuild") { dependsOn(prepareSpeechAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation(files("libs/sherpa-onnx-arm64.aar").builtBy(prepareSpeechAssets))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

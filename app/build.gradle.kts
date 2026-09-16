import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Both SDKs ship libonnxruntime.so but require different versioned C symbols.
// Namespace Moonshine's matching runtime instead of picking/replacing a library.
val moonshineSdk by configurations.creating { isTransitive = false }
val sherpaSdk by configurations.creating { isTransitive = false }
val sherpaDir = layout.buildDirectory.dir("sherpa-sdk")
val sherpaNativeDir = layout.buildDirectory.dir("sherpa-native")
val extractSherpa by tasks.registering(Exec::class) {
    inputs.files(sherpaSdk)
    inputs.file(rootProject.file("scripts/prepare_sherpa_sdk.py"))
    outputs.dir(sherpaDir)
    doFirst {
        commandLine("python3", rootProject.file("scripts/prepare_sherpa_sdk.py"),
            sherpaSdk.singleFile, sherpaDir.get().asFile)
    }
}
val buildSherpa by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("scripts/build_sherpa.py"), rootProject.file("native/sherpa/pocket-streaming.patch"))
    outputs.dir(sherpaNativeDir.map { it.dir("jni") })
    doFirst {
        commandLine("python3", rootProject.file("scripts/build_sherpa.py"), "--output", sherpaNativeDir.get().asFile,
            "--android-ndk", File(android.sdkDirectory, "ndk/27.2.12479018"))
    }
}
val moonshineDir = layout.buildDirectory.dir("moonshine-sdk")
val extractMoonshine by tasks.registering(Exec::class) {
    inputs.files(moonshineSdk)
    inputs.file(rootProject.file("scripts/prepare_moonshine_sdk.py"))
    outputs.dir(moonshineDir)
    doFirst {
        commandLine("python3", rootProject.file("scripts/prepare_moonshine_sdk.py"),
            moonshineSdk.singleFile, moonshineDir.get().asFile)
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(extractMoonshine, extractSherpa, buildSherpa) }
android {
    sourceSets.getByName("main").jniLibs.srcDir(sherpaNativeDir.map { it.dir("jni") })
    namespace = "com.battlesbudz.jarvis.v2"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/microwakeword/CMakeLists.txt"); version = "3.22.1" } }
    val buildVersionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
    val buildVersionName = System.getenv("ANDROID_VERSION_NAME") ?: "0.1.0"
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        applicationId = "com.battlesbudz.jarvis.v2"
        minSdk = 29
        targetSdk = 35
        // Jarvis is currently shipped for modern ARM64 Android phones.
        // Excluding unused x86/32-bit native runtimes keeps the APK much smaller.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
        versionCode = buildVersionCode
        versionName = buildVersionName
        val sourceCommit = System.getenv("GITHUB_SHA")?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) } ?: "unavailable"
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets.getByName("main").jniLibs.srcDir(moonshineDir.map { it.dir("jni") })
    packaging.jniLibs.excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    moonshineSdk("ai.moonshine:moonshine-voice:0.1.5@aar")
    implementation(files(moonshineDir.map { it.file("classes.jar") }).builtBy(extractMoonshine))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.12.0")
    sherpaSdk("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.7@aar")
    implementation(files(sherpaDir.map { it.file("classes.jar") }).builtBy(extractSherpa))
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

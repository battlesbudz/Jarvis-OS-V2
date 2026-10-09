import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The 4 .onnx models + synset.txt are NOT committed to Jarvis-OS-V2 git
// (54 MB). They are downloaded at build time from the pinned
// roboflow-phone-inference branch so the AAR stays self-contained and
// fully offline at runtime.
val modelBaseUrl =
    "https://raw.githubusercontent.com/battlesbudz/roboflow-phone-inference/feature/native-onnx-app"
val modelFiles = mapOf(
    "yolov8n.onnx" to "models/yolov8n.onnx",
    "yolov8n-seg.onnx" to "models/yolov8n-seg.onnx",
    "yolov8n-pose.onnx" to "models/yolov8n-pose.onnx",
    "mobilenetv2-12.onnx" to "models/mobilenetv2-12.onnx",
    "synset.txt" to "synset.txt",
)
val generatedAssets = layout.buildDirectory.dir("generated/assets")
val downloadVisionModels by tasks.registering {
    outputs.dir(generatedAssets)
    doLast {
        val dir = generatedAssets.get().asFile
        for ((remote, local) in modelFiles) {
            val target = dir.resolve(local)
            if (!target.exists()) {
                target.parentFile.mkdirs()
                java.net.URL("$modelBaseUrl/$remote").openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

android {
    namespace = "com.battlesbudz.phoneinference"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    sourceSets.getByName("main").assets.srcDir(generatedAssets)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadVisionModels) }

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

// ---------------------------------------------------------------------------
// Build-time model fetch: the 4 .onnx models + synset.txt (54 MB) are NOT
// committed to git. They are downloaded from the pinned roboflow-phone-
// inference branch into this module's generated assets, so the AAR stays
// self-contained and fully offline at runtime.
// ---------------------------------------------------------------------------
val modelBaseUrl =
    "https://raw.githubusercontent.com/battlesbudz/roboflow-phone-inference/feature/native-onnx-app"
val modelFiles = mapOf(
    "yolov8n.onnx" to "models/yolov8n.onnx",
    "yolov8n-seg.onnx" to "models/yolov8n-seg.onnx",
    "yolov8n-pose.onnx" to "models/yolov8n-pose.onnx",
    "mobilenetv2-12.onnx" to "models/mobilenetv2-12.onnx",
    "synset.txt" to "synset.txt",
)
val generatedAssetsDir = layout.buildDirectory.dir("generated/vision-assets")

val downloadVisionModels: TaskProvider<Task> = tasks.register("downloadVisionModels") {
    group = "build"
    description = "Downloads the native ONNX vision models and synset into generated assets."
    outputs.dir(generatedAssetsDir)
    doLast {
        val outDir = generatedAssetsDir.get().asFile
        for ((remote, local) in modelFiles) {
            val target = outDir.resolve(local)
            if (!target.exists()) {
                target.parentFile.mkdirs()
                URL("$modelBaseUrl/$remote").openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

android.sourceSets.getByName("main").assets.srcDir(generatedAssetsDir)

// Ensure the models are downloaded before any build work reads the assets.
tasks.named("preBuild") {
    dependsOn(downloadVisionModels)
}

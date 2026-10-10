import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The app already ships sherpa's libonnxruntime.so (1.27.1), whose versioned
// OrtGetApiBase symbols are ABI-incompatible with this module's 1.22.0 JNI
// bridge (same reason Moonshine's runtime copy is namespaced). Resolve the
// AAR through a detached configuration, compile against only its
// classes.jar, and ship a namespaced private copy of its native libs
// (scripts/prepare_vision_ort.py) so the two runtimes never collide.
val visionOrtSdk by configurations.creating { isTransitive = false }
val visionOrtDir = layout.buildDirectory.dir("vision-ort-sdk")
val extractVisionOrt by tasks.registering(Exec::class) {
    inputs.files(visionOrtSdk)
    inputs.file(rootProject.file("scripts/prepare_vision_ort.py"))
    outputs.dir(visionOrtDir)
    doFirst {
        commandLine("python3", rootProject.file("scripts/prepare_vision_ort.py"),
            visionOrtSdk.singleFile, visionOrtDir.get().asFile)
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
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(visionOrtDir.map { it.dir("jni") })
}

dependencies {
    visionOrtSdk("com.microsoft.onnxruntime:onnxruntime-android:1.22.0@aar")
    implementation(files(visionOrtDir.map { it.file("classes.jar") }).builtBy(extractVisionOrt))
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

// Ensure the models are downloaded and the namespaced ORT native libs are
// extracted before any build work reads the assets or packages jniLibs.
tasks.named("preBuild") {
    dependsOn(downloadVisionModels, extractVisionOrt)
}

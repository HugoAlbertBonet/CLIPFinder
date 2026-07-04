import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.halbertb.clipfinder"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.halbertb.clipfinder"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

val cargoBuildTurbovec by tasks.registering(Exec::class) {
    val cargo = "${System.getProperty("user.home")}/.cargo/bin/cargo"
    val androidSdk = android.sdkDirectory
    val ndkDir = androidSdk.resolve("ndk/27.2.12479018")
    workingDir = file("src/main/rust/turbovec_jni")
    environment("ANDROID_HOME", androidSdk.absolutePath)
    environment("ANDROID_SDK_ROOT", androidSdk.absolutePath)
    environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
    environment("PATH", "${System.getProperty("user.home")}/.cargo/bin:${System.getenv("PATH")}")
    commandLine(
        cargo,
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        file("src/main/jniLibs").absolutePath,
        "--platform",
        "26",
        "build",
        "--release",
    )
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.register("downloadClipBpe") {
    val dest = file("src/main/assets/clip/bpe_simple_vocab_16e6.txt.gz")
    outputs.file(dest)
    doLast {
        dest.parentFile.mkdirs()
        if (!dest.exists()) {
            URI(
                "https://github.com/openai/CLIP/raw/refs/heads/main/clip/bpe_simple_vocab_16e6.txt.gz",
            ).toURL().openStream().use { input: java.io.InputStream ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

tasks.register("downloadClipOnnxModels") {
    val modelsDir = file("src/main/assets/models")
    val vision = modelsDir.resolve("vision_model_quantized.onnx")
    val text = modelsDir.resolve("text_model_quantized.onnx")
    outputs.files(vision, text)
    doLast {
        modelsDir.mkdirs()
        fun download(url: String, out: java.io.File) {
            if (out.exists()) return
            URI(url).toURL().openStream().use { input: java.io.InputStream ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        download(
            "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/vision_model_quantized.onnx",
            vision,
        )
        download(
            "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/onnx/text_model_quantized.onnx",
            text,
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn("downloadClipBpe")
    dependsOn(cargoBuildTurbovec)
}

import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing data comes from keystore.properties (git-ignored).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// --- Native adblock engine (Brave's adblock-rust, see adblock-native/) ------------------------

val ndkVersionPinned = "28.2.13676358"
val rustAbis = listOf("arm64-v8a", "x86_64")

val sdkDir: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: error("Android SDK not found: set sdk.dir in local.properties")

/** Runs `cargo ndk` and writes lib<name>.so per ABI into [outputDir]. */
abstract class CargoNdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal abstract val crateDir: DirectoryProperty

    /** Up-to-date check: crate sources and manifests (not target/). */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val ndkHome: Property<String>
    @get:Input abstract val minApi: Property<Int>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val args = mutableListOf("ndk")
        abis.get().forEach { args += listOf("-t", it) }
        args += listOf("-P", minApi.get().toString(), "-o", outputDir.get().asFile.absolutePath, "build", "--release")
        exec.exec {
            workingDir = crateDir.get().asFile
            executable = "cargo"
            args(args)
            environment("ANDROID_NDK_HOME", ndkHome.get())
        }
    }
}

val cargoBuildAdblock = tasks.register<CargoNdkTask>("cargoBuildAdblock") {
    group = "build"
    description = "Builds adblock-native (Rust) for ${rustAbis.joinToString()} via cargo-ndk"
    val crate = rootProject.layout.projectDirectory.dir("adblock-native")
    crateDir.set(crate)
    sources.from(crate.dir("src"), crate.file("Cargo.toml"), crate.file("Cargo.lock"))
    abis.set(rustAbis)
    ndkHome.set(File(sdkDir, "ndk/$ndkVersionPinned").absolutePath)
    minApi.set(26)
    outputDir.set(layout.buildDirectory.dir("rustJniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildAdblock, CargoNdkTask::outputDir)
    }
}

android {
    namespace = "com.moddingforge.truetube"
    compileSdk = 37
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "com.moddingforge.truetube"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Only the ABIs we build the Rust library for.
        ndk { abiFilters += rustAbis }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "TrueTube Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "TrueTube")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.media3.session)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

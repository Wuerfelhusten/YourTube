import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: keystore.properties (local, git-ignored) or environment variables (CI, from
// GitHub secrets): YOURTUBE_KEYSTORE_FILE, YOURTUBE_KEYSTORE_PASSWORD, YOURTUBE_KEY_ALIAS, YOURTUBE_KEY_PASSWORD.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
data class ReleaseKey(val file: File, val storePassword: String, val alias: String, val keyPassword: String)
val releaseKey: ReleaseKey? = run {
    val env = System.getenv()
    val envFile = env["YOURTUBE_KEYSTORE_FILE"]
    when {
        !envFile.isNullOrBlank() -> ReleaseKey(
            file(envFile),
            env["YOURTUBE_KEYSTORE_PASSWORD"].orEmpty(),
            env["YOURTUBE_KEY_ALIAS"].orEmpty(),
            env["YOURTUBE_KEY_PASSWORD"].orEmpty(),
        )
        keystoreProps.isNotEmpty() -> ReleaseKey(
            rootProject.file(keystoreProps.getProperty("storeFile")),
            keystoreProps.getProperty("storePassword"),
            keystoreProps.getProperty("keyAlias"),
            keystoreProps.getProperty("keyPassword"),
        )
        else -> null
    }
}
// CI passes -Pyourtube.requireReleaseKey=true: a release signed with anything but the real key
// could never update an installed app, so such a build must fail instead of falling back.
val requireReleaseKey = providers.gradleProperty("yourtube.requireReleaseKey").map { it.toBoolean() }.getOrElse(false)
if (requireReleaseKey && (releaseKey == null || !releaseKey.file.exists())) {
    throw GradleException("Release signing key missing (YOURTUBE_KEYSTORE_FILE / keystore.properties)")
}

// App version: single source in gradle.properties (yourtube.versionName = MAJOR.MINOR.PATCH).
// The versionCode is derived from it, so every higher version installs as an update.
val appVersionName: String = providers.gradleProperty("yourtube.versionName").get()
val appVersionCode: Int = run {
    val parts = appVersionName.split('.').map { it.toIntOrNull() }
    if (parts.size != 3 || parts.any { it == null || it < 0 || it > 999 }) {
        throw GradleException("yourtube.versionName must be MAJOR.MINOR.PATCH (each 0..999), got '$appVersionName'")
    }
    parts[0]!! * 1_000_000 + parts[1]!! * 1_000 + parts[2]!!
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

/** Copies the repository's CHANGELOG.md into the APK assets (shown in the app after updates). */
abstract class ChangelogAssetTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val changelog: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        changelog.get().asFile.copyTo(File(out, "CHANGELOG.md"), overwrite = true)
    }
}

val changelogAsset = tasks.register<ChangelogAssetTask>("changelogAsset") {
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    outputDir.set(layout.buildDirectory.dir("generated/changelogAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildAdblock, CargoNdkTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(changelogAsset, ChangelogAssetTask::outputDir)
    }
}

android {
    namespace = "com.moddingforge.yourtube"
    compileSdk = 37
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "com.moddingforge.yourtube"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // Only the ABIs we build the Rust library for.
        ndk { abiFilters += rustAbis }

        // In-app updater source (gradle.properties: yourtube.updateRepo=owner/name).
        buildConfigField("String", "UPDATE_REPO", "\"${providers.gradleProperty("yourtube.updateRepo").getOrElse("")}\"")
    }

    signingConfigs {
        releaseKey?.let { key ->
            create("release") {
                storeFile = key.file
                storePassword = key.storePassword
                keyAlias = key.alias
                keyPassword = key.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "YourTube Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "YourTube")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val versionPropertiesFile = rootProject.file("version.properties")
if (!versionPropertiesFile.isFile) {
    throw GradleException("Missing application version configuration: ${versionPropertiesFile.path}")
}
val versionProperties = Properties().apply {
    versionPropertiesFile.inputStream().use(::load)
}

fun versionProperty(name: String): String =
    versionProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Missing $name in ${versionPropertiesFile.path}")

val defaultVersionCode = versionProperty("appVersionCode")
val defaultVersionName = versionProperty("appVersionName")

val releaseSigningPropertiesFile = rootProject.file("release-signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningProperty(name: String): String? =
    releaseSigningProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

fun validatedVersionName(raw: String): String = raw.takeIf {
    it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))
} ?: throw GradleException(
    "appVersionName must start with an ASCII letter or digit and contain only ASCII letters, digits, '.', '_', '+', or '-'",
)

val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val configuredReleaseSigningKeys = releaseSigningKeys.filter { releaseSigningProperty(it) != null }
val releaseSigningConfigured = configuredReleaseSigningKeys.size == releaseSigningKeys.size
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning")
    .map { it.toBooleanStrictOrNull() ?: throw GradleException("requireReleaseSigning must be true or false") }
    .getOrElse(false)

if (configuredReleaseSigningKeys.isNotEmpty() && !releaseSigningConfigured) {
    throw GradleException(
        "Incomplete release signing configuration; provide storeFile, storePassword, keyAlias, and keyPassword together",
    )
}
if (requireReleaseSigning && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is required; create release-signing.properties before building this variant",
    )
}

val releaseKeystoreFile = releaseSigningProperty("storeFile")?.let(rootProject::file)
if (releaseSigningConfigured && releaseKeystoreFile?.isFile != true) {
    throw GradleException("Release keystore does not exist: ${releaseKeystoreFile?.path}")
}

fun validatedVersionCode(raw: String): Int = raw.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
    ?: throw GradleException("appVersionCode must be an integer from 1 to 2100000000")

android {
    namespace = "com.lanshare"
    compileSdk = 37
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.lanshare"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("appVersionCode")
            .orElse(defaultVersionCode)
            .map(::validatedVersionCode)
            .get()
        versionName = providers.gradleProperty("appVersionName")
            .orElse(defaultVersionName)
            .map(::validatedVersionName)
            .get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = releaseKeystoreFile
                storePassword = releaseSigningProperty("storePassword")
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

/**
 * Packages the built web client as `assets/web/...` for the local browser
 * bridge. `web/public-ui/dist` is generated (and gitignored), so fail with a
 * clear instruction instead of shipping an APK whose bridge has no page.
 */
abstract class SyncWebUi : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun sync() {
        val sourceDir = source.get().asFile
        if (!sourceDir.resolve("index.html").isFile) {
            throw GradleException(
                "web/public-ui/dist/index.html is missing. Run: cd web/public-ui && npm install && npm run build",
            )
        }
        val destination = output.get().asFile.resolve("web")
        destination.deleteRecursively()
        destination.parentFile?.mkdirs()
        sourceDir.copyRecursively(destination, overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val taskName = "sync${variant.name.replaceFirstChar { it.uppercase() }}WebUi"
        val syncTask = tasks.register<SyncWebUi>(taskName) {
            source.set(rootProject.layout.projectDirectory.dir("../web/public-ui/dist"))
            output.set(layout.buildDirectory.dir("generated/${variant.name}/webUiAssets"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(syncTask, SyncWebUi::output)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.webrtc)
    implementation(libs.zxing.core)
    implementation(libs.google.mlkit.barcode.scanning)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}

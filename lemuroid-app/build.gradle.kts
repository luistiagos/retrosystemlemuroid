import com.swordfish.lemuroid.builder.PrebuiltDbGenerator
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("androidx.navigation.safeargs.kotlin")
    id("kotlinx-serialization")
    id("androidx.baselineprofile")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun readLocalProperty(key: String): String {
    val localPropertiesFile = rootProject.file("local.properties")
    if (!localPropertiesFile.exists()) return ""

    val properties = Properties()
    localPropertiesFile.inputStream().use { properties.load(it) }
    return properties.getProperty(key, "")
}

fun escapeBuildConfigValue(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

fun readGradleProperty(key: String): String? =
    providers.gradleProperty(key).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun sanitizeFileToken(value: String): String =
    value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

fun appUpdateEndpointForChannel(baseUrl: String, channel: String): String {
    if (channel == "default") return baseUrl
    return "${baseUrl.trimEnd('/')}/$channel"
}

fun normalizeApplicationIdSuffix(value: String?): String {
    val suffix = value?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
    return if (suffix.startsWith(".")) suffix else ".$suffix"
}

val catalogChannel = readGradleProperty("catalogChannel") ?: "default"
val appUpdateChannel = readGradleProperty("appUpdateChannel") ?: catalogChannel
val appUpdateBaseUrl = readGradleProperty("appUpdateBaseUrl")
    ?: "https://emuladores.pythonanywhere.com/app_version"
val appUpdateEndpoint = readGradleProperty("appUpdateEndpoint")
    ?: appUpdateEndpointForChannel(appUpdateBaseUrl, appUpdateChannel)
val catalogManifestOverride = readGradleProperty("catalogManifest")
val catalogManifestFile = catalogManifestOverride
    ?.let { rootProject.file(it) }
    ?: project.file("src/main/assets/catalog_manifest.txt")
val catalogManifestAssetName = if (catalogManifestOverride == null) {
    "catalog_manifest.txt"
} else {
    "catalog_manifest_${sanitizeFileToken(catalogChannel)}.txt"
}
val catalogApplicationIdSuffix = normalizeApplicationIdSuffix(readGradleProperty("catalogApplicationIdSuffix"))

android {
    defaultConfig {
        versionCode = 231
        versionName = "1.17.0" // Always remember to update Cores Tag!
        applicationId = "app.retrogamesystem$catalogApplicationIdSuffix"

        buildConfigField("String", "CATALOG_CHANNEL", "\"${escapeBuildConfigValue(catalogChannel)}\"")
        buildConfigField("String", "CATALOG_MANIFEST_ASSET", "\"${escapeBuildConfigValue(catalogManifestAssetName)}\"")
        buildConfigField("String", "APP_UPDATE_CHANNEL", "\"${escapeBuildConfigValue(appUpdateChannel)}\"")
        buildConfigField("String", "APP_UPDATE_ENDPOINT", "\"${escapeBuildConfigValue(appUpdateEndpoint)}\"")

        val archiveEmail = readLocalProperty("archive.email")
        val archivePassword = readLocalProperty("archive.password")
        buildConfigField("String", "ARCHIVE_EMAIL", "\"${escapeBuildConfigValue(archiveEmail)}\"")
        buildConfigField("String", "ARCHIVE_PASSWORD", "\"${escapeBuildConfigValue(archivePassword)}\"")
    }
    flavorDimensions += listOf("opensource", "cores")

    if (usePlayDynamicFeatures()) {
        println("Building Google Play version. Bundling dynamic features.")
        dynamicFeatures.addAll(
            setOf(
                ":lemuroid_core_a5200",
                ":lemuroid_core_desmume",
                ":lemuroid_core_dosbox_pure",
                ":lemuroid_core_fbneo",
                ":lemuroid_core_fceumm",
                ":lemuroid_core_gambatte",
                ":lemuroid_core_genesis_plus_gx",
                ":lemuroid_core_handy",
                ":lemuroid_core_mame2003_plus",
                ":lemuroid_core_mednafen_ngp",
                ":lemuroid_core_mednafen_pce_fast",
                ":lemuroid_core_mednafen_wswan",
                ":lemuroid_core_melonds",
                ":lemuroid_core_mgba",
                ":lemuroid_core_mupen64plus_next_gles3",
                ":lemuroid_core_pcsx_rearmed",
                ":lemuroid_core_ppsspp",
                ":lemuroid_core_prosystem",
                ":lemuroid_core_snes9x",
                ":lemuroid_core_stella",
                ":lemuroid_core_citra",
                ":lemuroid_core_gearcoleco",
                ":lemuroid_core_flycast",
                ":lemuroid_core_opera",
                ":lemuroid_core_fake08",
                ":lemuroid_core_vircon32",
                ":lemuroid_core_picodrive",
                ":lemuroid_core_atari800",
                ":lemuroid_core_sameduck",
                ":lemuroid_core_freechaf",
                ":lemuroid_core_uzem",
                ":lemuroid_core_lowresnx",
                ":lemuroid_core_arduous",
                ":lemuroid_core_dolphin",
                ":lemuroid_core_yabasanshiro",
                ":lemuroid_core_virtualjaguar",
                ":lemuroid_core_o2em",
                ":lemuroid_core_neocd",
                ":lemuroid_core_puae",
                ":lemuroid_core_mednafen_pcfx",
                ":lemuroid_core_gw",
                ":lemuroid_core_hatari",
            ),
        )
    }

    // Since some dependencies are closed source we make a completely free as in free speech variant.

    productFlavors {

        create("free") {
            dimension = "opensource"
        }

        create("play") {
            dimension = "opensource"
        }

        // Include cores in the final apk
        create("bundle") {
            dimension = "cores"
        }

        // Download cores on demand (from GooglePlay or GitHub)
        create("dynamic") {
            dimension = "cores"
        }
    }

    packagingOptions {
        jniLibs {
            // Stripping created some issues with some libretro cores such as ppsspp
            keepDebugSymbols += setOf("*/*/*_libretro_android.so")
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/library_release.kotlin_module")
        }
    }

    signingConfigs {
        maybeCreate("debug").apply {
            storeFile = file("$rootDir/debug.keystore")
        }

        maybeCreate("release").apply {
            // Release is signed with the debug keystore on purpose: there is no separate
            // release.jks in this repo, and the release package id (app.retrogamesystem)
            // differs from debug (.debug suffix), so there is no update-signature conflict.
            // Swap back to a dedicated release.jks here if a distribution key is introduced.
            storeFile = file("$rootDir/debug.keystore")
            keyAlias = "androiddebugkey"
            storePassword = "android"
            keyPassword = "android"
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs["release"]
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            resValue("string", "lemuroid_name", "Retro Game System")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            resValue("string", "lemuroid_name", "Retro Game System Debug")
        }
    }

    lint {
        disable += setOf("MissingTranslation", "ExtraTranslation", "EnsureInitializerMetadata")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = deps.versions.kotlinExtension
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    namespace = "com.swordfish.lemuroid"
}

androidComponents {
    beforeVariants(selector().withFlavor("opensource" to "play")) { variantBuilder ->
        variantBuilder.enable = false
    }
}

dependencies {
    implementation(project(":retrograde-util"))
    implementation(project(":retrograde-app-shared"))
    implementation(project(":lemuroid-metadata-libretro-db"))
    implementation(project(":lemuroid-touchinput"))

    "baselineProfile"(project(":baselineprofile"))
    implementation(deps.libs.androidx.profileInstaller)

    "bundleImplementation"(project(":bundled-cores"))

    "freeImplementation"(project(":lemuroid-app-ext-free"))
    "playImplementation"(project(":lemuroid-app-ext-play"))

    implementation(deps.libs.androidx.navigation.navigationFragment)
    implementation(deps.libs.androidx.navigation.navigationUi)
    implementation(deps.libs.androidx.navigation.compose)
    implementation(deps.libs.material)
    implementation(deps.libs.coil.coil)
    implementation(deps.libs.coil.coilCompose)
    implementation(deps.libs.androidx.appcompat.constraintLayout)
    implementation(deps.libs.androidx.activity.activity)
    implementation(deps.libs.androidx.activity.activityKtx)
    implementation(deps.libs.androidx.activity.compose)
    implementation(deps.libs.androidx.appcompat.appcompat)
    implementation(deps.libs.androidx.preferences.preferencesKtx)
    implementation(deps.libs.arch.work.runtime)
    implementation(deps.libs.arch.work.runtimeKtx)
    implementation(deps.libs.androidx.lifecycle.commonJava8)
    implementation(deps.libs.androidx.lifecycle.reactiveStreams)

    kapt(deps.libs.androidx.lifecycle.processor)

    implementation(deps.libs.androidx.leanback.leanback)
    implementation(deps.libs.androidx.leanback.leanbackPreference)
    implementation(deps.libs.androidx.leanback.leanbackPaging)

    implementation(deps.libs.androidx.appcompat.recyclerView)
    implementation(deps.libs.androidx.paging.common)
    implementation(deps.libs.androidx.paging.runtime)
    implementation(deps.libs.androidx.room.common)
    implementation(deps.libs.androidx.room.runtime)
    implementation(deps.libs.androidx.room.ktx)
    implementation(deps.libs.dagger.android.core)
    implementation(deps.libs.dagger.android.support)
    implementation(deps.libs.dagger.core)
    implementation(deps.libs.kotlinxCoroutinesAndroid)
    implementation(deps.libs.okHttp3)
    implementation(deps.libs.conscrypt)
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9")
    implementation(deps.libs.okio)
    implementation(deps.libs.retrofit)
    implementation(deps.libs.flowPreferences)
    implementation(deps.libs.guava)
    implementation(deps.libs.androidx.documentfile)
    implementation(deps.libs.androidx.leanback.tvProvider)
    implementation(deps.libs.harmony)
    implementation(deps.libs.startup)
    implementation(deps.libs.splashscreen)
    implementation(deps.libs.kotlin.serialization)
    implementation(deps.libs.kotlin.serializationJson)

    implementation(platform(deps.libs.androidx.compose.composeBom))
    implementation(deps.libs.androidx.compose.material3)
    implementation(deps.libs.androidx.compose.constraintLayout)
    debugImplementation(deps.libs.androidx.compose.tooling)
    implementation(deps.libs.androidx.compose.toolingPreview)
    implementation(deps.libs.androidx.compose.extendedIcons)
    implementation(deps.libs.androidx.compose.accompanist.systemUiController)
    implementation(deps.libs.androidx.compose.accompanist.navigationMaterial)
    implementation(deps.libs.androidx.compose.accompanist.drawablePainter)
    implementation(deps.libs.androidx.paging.compose)
    implementation(deps.libs.androidx.lifecycle.viewModelCompose)
    implementation(deps.libs.composeHtmlText)

    implementation(deps.libs.composeSettings.uiTiles)
    implementation(deps.libs.composeSettings.uiTilesExtended)
    implementation(deps.libs.composeSettings.diskStorage)
    implementation(deps.libs.composeSettings.memoryStorage)

    // Patched LibretroDroid AAR local (path em deps.libs.libretrodroid). Inclui fallback
    // EGLConfigChooser para Smart TVs cujo driver não satisfaz a config EGL padrão do GLSurfaceView.
    implementation(files(rootProject.file(deps.libs.libretrodroid)))
    // Transitive deps do POM original do JitPack que precisamos declarar manualmente.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${deps.versions.lifecycle}")

    kapt(deps.libs.dagger.android.processor)
    kapt(deps.libs.dagger.compiler)
}

// Convenience task: installs the debug APK and then AOT-compiles it so that
// baseline-profile-like performance can be tested without a release build.
// Usage:  ./gradlew installFreeBundleDebugAndCompile
android.applicationVariants.all {
    if (buildType.isDebuggable) {
        val variantName = name.replaceFirstChar { it.uppercase() }
        val capturedAppId: String = applicationId
        val capturedAdbPath: String = android.adbExecutable.absolutePath
        tasks.register<Exec>("install${variantName}AndCompile") {
            dependsOn("install$variantName")
            commandLine(
                capturedAdbPath,
                "shell", "cmd", "package", "compile",
                "-m", "speed", "-f", capturedAppId,
            )
        }
    }
}

fun usePlayDynamicFeatures(): Boolean {
    val task = gradle.startParameter.taskRequests.toString()
    return task.contains("Play") && task.contains("Dynamic")
}

// ── Prebuilt DB generation ───────────────────────────────────────────────────
// Generates `assets/retrograde-prebuilt.db` from catalog_manifest.txt at build time,
// using the Room schema (schemas/24.json) as the source of truth for DDL + identityHash.
// The app uses Room.databaseBuilder(...).createFromAsset() on first launch to copy this
// into place instead of running 29k+ INSERTs through Room — eliminates the "preparando
// ambiente" wait on fresh installs.

val prebuiltDbOutputDir = layout.buildDirectory.dir("generated/prebuiltDb")
val prebuiltDbFile = prebuiltDbOutputDir.map { it.file("retrograde-prebuilt.db") }
val generatedCatalogRootDir = layout.buildDirectory.dir("generated/catalogManifest")
val generatedCatalogAssetsDir = generatedCatalogRootDir.map { it.dir("assets") }
val generatedCatalogPrebuiltDir = generatedCatalogRootDir.map { it.dir("prebuilt") }

val prepareCatalogManifestAsset = tasks.register<Copy>("prepareCatalogManifestAsset") {
    onlyIf { catalogManifestOverride != null }
    val manifestAliasFile = project.file("src/main/assets/manifest_alias.json")

    into(generatedCatalogRootDir)
    from(catalogManifestFile) {
        into("assets")
        rename { catalogManifestAssetName }
    }
    from(catalogManifestFile) {
        into("prebuilt")
        rename { catalogManifestAssetName }
    }
    from(manifestAliasFile) {
        into("prebuilt")
    }
}

val generatePrebuiltDb = tasks.register("generatePrebuiltDb") {
    if (catalogManifestOverride != null) {
        dependsOn(prepareCatalogManifestAsset)
    }

    val schemaJson = rootProject.file(
        "retrograde-app-shared/schemas/com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase/24.json",
    )
    val manifestFile = if (catalogManifestOverride == null) {
        catalogManifestFile
    } else {
        generatedCatalogPrebuiltDir.get().file(catalogManifestAssetName).asFile
    }
    val manifestAliasFile = if (catalogManifestOverride == null) {
        project.file("src/main/assets/manifest_alias.json")
    } else {
        generatedCatalogPrebuiltDir.get().file("manifest_alias.json").asFile
    }
    val outputFile = prebuiltDbFile.get().asFile

    inputs.file(schemaJson)
    inputs.file(manifestFile)
    inputs.file(manifestAliasFile)
    inputs.files(rootProject.fileTree("buildSrc/src/main/kotlin"))
    outputs.file(outputFile)

    doLast {
        PrebuiltDbGenerator.generate(
            schemaJsonFile = schemaJson,
            manifestFile = manifestFile,
            outputDbFile = outputFile,
        )
    }
}

android {
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", prebuiltDbOutputDir)
            if (catalogManifestOverride != null) {
                assets.srcDir(generatedCatalogAssetsDir)
            }
        }
    }
}

// Any task that reads the assets directory (mergeAssets, packageRelease, lintAnalyze*, etc.)
// must run after generatePrebuiltDb. Naming patterns cover assets, lint, and bundle tasks
// across all flavor/build-type variants.
afterEvaluate {
    tasks.matching { task ->
        val n = task.name
        n.contains("Assets", ignoreCase = true) ||
            n.startsWith("lint", ignoreCase = true) ||
            n.startsWith("package", ignoreCase = true) ||
            n.startsWith("bundle", ignoreCase = true)
    }.configureEach {
        if (name != generatePrebuiltDb.name) {
            dependsOn(generatePrebuiltDb)
        }
        if (catalogManifestOverride != null && name != prepareCatalogManifestAsset.name) {
            dependsOn(prepareCatalogManifestAsset)
        }
    }
}

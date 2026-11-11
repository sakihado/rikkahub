import com.android.build.api.dsl.Packaging
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "me.rerere.rikkahub"
        minSdk = 26
        targetSdk = 37
        versionCode = 196
        versionName = "3.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            // 没有配置签名（local.properties 里没有 storeFile）时产出 unsigned apk，
            // 由构建流程在本地另行签名，避免 validateSigning 直接失败
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            optimization {
                enable = true
            }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
    }
    // 按分发渠道区分：github 是 GitHub Release / 每日构建的 APK，play 是上架 Google Play 的 AAB
    // 两个渠道共用同一个 applicationId，Play 版不能自更新，相关入口通过 BuildConfig.IS_PLAY_STORE 关闭
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "IS_PLAY_STORE", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_STORE", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
        // 只保留 app 自己翻译过的语言，依赖库（AndroidX、GMS 等）带的其他语言资源不打包
        localeFilters += listOf("en", "ar", "de", "ja", "ko", "ko-rKR", "ru", "zh", "zh-rTW")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // BouncyCastle 的 Picnic 后量子签名查找表（约 1.2MB），JSch 用不到
            excludes += "org/bouncycastle/pqc/crypto/picnic/**"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

// 两个渠道的启动路径一样，profile 合并到 src/main 共用，不按变体各存一份
baselineProfile {
    mergeIntoMain = true
}

// baselineprofile 插件按 release 复制出 nonMinifiedRelease 时只关掉 isMinifyEnabled，关不掉 optimization，
// 不手动关的话生成用的包会被 R8 内联、合并类，profile 里记下的是优化后的类和方法
abstract class CompileShelldropJniLibsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:Internal
    abstract val sdkDirectoryHint: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val ndkDir = resolveNdkDirectory(sdkDirectoryHint.orNull)
            ?: throw GradleException(
                "Android NDK not found for compiling libshelldrop.so. " +
                    "Set ANDROID_NDK_HOME / ANDROID_NDK_LATEST_HOME or install NDK under Android SDK/ndk."
            )
        val prebuiltRoot = File(ndkDir, "toolchains/llvm/prebuilt")
        val hostBinDir = prebuiltRoot.listFiles()
            ?.firstOrNull { File(it, "bin").isDirectory }
            ?.let { File(it, "bin") }
            ?: throw GradleException("LLVM toolchain prebuilt bin directory not found under $prebuiltRoot")

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val ext = if (isWindows) ".cmd" else ""

        val targets = mapOf(
            "arm64-v8a" to "aarch64-linux-android26-clang$ext",
            "x86_64" to "x86_64-linux-android26-clang$ext",
        )

        val outRoot = outputDir.get().asFile
        outRoot.deleteRecursively()

        val src = sourceFile.get().asFile
        for ((abi, compilerName) in targets) {
            val clang = File(hostBinDir, compilerName)
            if (!clang.exists()) {
                throw GradleException("NDK compiler missing: ${clang.absolutePath}")
            }
            val abiDir = File(outRoot, abi).apply { mkdirs() }
            val outSo = File(abiDir, "libshelldrop.so")
            val cmd = listOf(
                clang.absolutePath,
                "-O2",
                "-Wall",
                "-Wextra",
                "-fPIE",
                "-pie",
                "-Wl,-z,max-page-size=16384",
                src.absolutePath,
                "-o",
                outSo.absolutePath,
            )
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0 || !outSo.isFile) {
                throw GradleException("Failed to compile libshelldrop.so for $abi (exit $code):\n$log")
            }
        }
    }

    private fun resolveNdkDirectory(sdkHint: String?): File? {
        val envCandidates = listOfNotNull(
            System.getenv("ANDROID_NDK_LATEST_HOME"),
            System.getenv("ANDROID_NDK_HOME"),
            System.getenv("ANDROID_NDK_ROOT"),
            System.getenv("NDK_HOME"),
        ).map(::File).filter { it.isDirectory }
        if (envCandidates.isNotEmpty()) return envCandidates.first()

        val sdkRoots = listOfNotNull(
            sdkHint,
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
        ).map(::File).filter { it.isDirectory }

        for (sdk in sdkRoots) {
            val ndkParent = File(sdk, "ndk")
            val latest = ndkParent.listFiles()
                ?.filter { it.isDirectory && File(it, "toolchains/llvm/prebuilt").isDirectory }
                ?.maxByOrNull { it.name }
            if (latest != null) return latest
            val legacy = File(sdk, "ndk-bundle")
            if (File(legacy, "toolchains/llvm/prebuilt").isDirectory) return legacy
        }
        return null
    }
}

abstract class CompileSafeSdcardAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scriptFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:Input
    abstract val requireOutputs: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val outRoot = outputDir.get().asFile
        outRoot.deleteRecursively()
        val safesdcardDir = File(outRoot, "safesdcard").apply { mkdirs() }

        val proc = ProcessBuilder(
            "bash",
            scriptFile.get().asFile.absolutePath,
            safesdcardDir.absolutePath,
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (log.isNotBlank()) {
            logger.lifecycle(log.trim())
        }
        if (exit != 0) {
            throw GradleException("build-safesdcard.sh failed (exit $exit):\n$log")
        }

        if (requireOutputs.get()) {
            listOf("libsafesdcard_arm64.so", "libsafesdcard_x86_64.so").forEach { name ->
                val f = File(safesdcardDir, name)
                if (!f.isFile || f.length() == 0L) {
                    throw GradleException(
                        "Required PRoot trash-guard library missing: ${f.absolutePath}. " +
                            "Install gcc & gcc-aarch64-linux-gnu on the build host."
                    )
                }
            }
        }
    }
}

val localSdkDirHint: String = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.let { propsFile ->
        Properties().apply { propsFile.inputStream().use(::load) }.getProperty("sdk.dir")
    }
    .orEmpty()

val compileShelldropJniLibs = tasks.register<CompileShelldropJniLibsTask>("compileShelldropJniLibs") {
    sourceFile.set(project.layout.projectDirectory.file("shelldrop/shelldrop.c"))
    sdkDirectoryHint.set(localSdkDirHint)
    outputDir.set(project.layout.buildDirectory.dir("generated/shelldropJniLibs"))
}

val compileSafeSdcardAssets = tasks.register<CompileSafeSdcardAssetsTask>("compileSafeSdcardAssets") {
    scriptFile.set(rootProject.layout.projectDirectory.file("workspace/safesdcard/build-safesdcard.sh"))
    sourceFile.set(rootProject.layout.projectDirectory.file("workspace/safesdcard/safesdcard.c"))
    requireOutputs.set(
        providers.gradleProperty("requireSafeSdcard")
            .map { it.equals("true", ignoreCase = true) }
            .orElse(false)
    )
    outputDir.set(project.layout.buildDirectory.dir("generated/safesdcardAssets"))
}

androidComponents {
    finalizeDsl { extension ->
        extension.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
    }
    onVariants(selector().withFlavor("distribution" to "github")) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            compileShelldropJniLibs,
            CompileShelldropJniLibsTask::outputDir,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            compileSafeSdcardAssets,
            CompileSafeSdcardAssetsTask::outputDir,
        )
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleGithubRelease", "bundlePlayRelease")
    description = "Build GitHub APK and Play AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// kotlin-reflect 只由 ktor-server-core 传递引入，用到它的只有按配置加载模块、开发模式热重载等用不到的路径。
// 它在 classpath 上时 stdlib 的 Reflection 会 Class.forName 加载 ReflectionFactoryImpl，R8 因此保留整个实现（约 0.7MB dex）
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// Local JVM tests need the desktop native library instead of the Android AAR.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(libs.quickjs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view) {
        // terminal-emulator 由仓库内的 :terminal 模块提供, 不再引入上游 aar
        exclude(group = "com.termux.termux-app", module = "terminal-emulator")
    }
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // EXIF orientation for picked images
    implementation(libs.androidx.exifinterface)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur and glass)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)
    implementation(libs.haze.glass)
    implementation(libs.haze.glass.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // zxing + CameraX (qrcode scanner)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // media3 (video playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // icons
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/re-ovo/bettertext
    implementation(platform(libs.bettertext.bom))
    implementation(libs.bettertext.text)
    implementation(libs.bettertext.markdown)
    implementation(libs.bettertext.latex)
    implementation(libs.bettertext.highlight)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // Shizuku / Sui privileged Binder IPC & Android hidden API exemption
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":mediagen"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":ui"))
    implementation(project(":workspace"))
    implementation(project(":terminal"))
    implementation(project(":oauth"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

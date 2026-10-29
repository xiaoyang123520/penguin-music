import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.compose") version "1.8.0"
    id("com.google.protobuf") version "0.9.4"
}

// -PwithApp=false 时只编 ncmapi + 桌面垫片（第一里程碑：数据链冒烟）
val withApp = (findProperty("withApp") as String?) != "false"

kotlin {
    jvmToolchain(17)
    sourceSets {
        val main = getByName("main")
        main.kotlin.srcDir("../ncmapi/src/main/java")
        if (withApp) {
            main.kotlin.srcDir("../app/src/main/java")
            main.kotlin.exclude(
                "com/jussicodes/music/MainActivity.kt",
                "com/jussicodes/music/JetMeloApp.kt",
                "com/jussicodes/music/di/**",
                "com/jussicodes/music/playback/PlaybackService.kt",
                "com/jussicodes/music/playback/AudioCache.kt",
                "com/jussicodes/music/playback/PlayerController.kt",
                "com/jussicodes/music/playback/audio/**",
                "com/jussicodes/music/lyric/DesktopLyricService.kt",
                "com/jussicodes/music/lyric/DesktopLyricManager.kt",
                "com/jussicodes/music/utils/AppUpdateManager.kt",
                "com/jussicodes/music/utils/UpdateDownloadService.kt",
                "com/jussicodes/music/utils/AppVisibilityTracker.kt",
                "com/jussicodes/music/utils/ImageSaveUtils.kt",
                "com/jussicodes/music/utils/ApiServerErrorNotifier.kt",
                "com/jussicodes/music/utils/DataStoreUtils.kt",
                "com/jussicodes/music/ui/screen/LoginScreen.kt",
            )
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") { option("lite") }
                register("kotlin") { option("lite") }
            }
        }
    }
}

sourceSets {
    getByName("main") {
        proto {
            srcDir("../app/src/main/proto")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.9.0")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.0")
    implementation("org.jetbrains.androidx.paging:paging-compose:3.3.0")

    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.datastore:datastore:1.1.4")

    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-okhttp:3.1.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-client-logging:3.1.1")
    implementation("io.ktor:ktor-client-encoding:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("com.google.protobuf:protobuf-kotlin-lite:4.29.1")
    implementation("com.google.protobuf:protobuf-javalite:4.29.1")
    implementation("com.github.luben:zstd-jni:1.5.6-6")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    implementation("javazoom:jlayer:1.0.1")
    implementation("javax.inject:javax.inject:1")

    // Windows 交付：把 Skiko Windows 运行时也打进 fat jar
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.9.10")
}

compose.desktop {
    application {
        mainClass = "com.jussicodes.music.desktop.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Jussichords"
            packageVersion = "2.4.5"
        }
    }
}

// 冒烟：只跑 ncmapi 数据链（搜索）验证真实接口
tasks.register<JavaExec>("smokeApi") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jussicodes.music.desktop.SmokeMainKt")
}

// fat jar（Windows 交付用，Launch4j 再包成 exe）
tasks.register<Jar>("fatJar") {
    group = "build"
    archiveBaseName.set("jussichords")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.jussicodes.music.desktop.DesktopMainKt"
    }
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

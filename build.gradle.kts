import net.kyori.indra.git.IndraGitExtension
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack

@Suppress("DSL_SCOPE_VIOLATION") // https://youtrack.jetbrains.com/issue/KTIJ-19369
plugins {
    application
    alias(libs.plugins.indra.git)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.spotless)
}

repositories {
    mavenCentral()
    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/") {
        name = "sonatype-oss-snapshots"
        mavenContent {
            snapshotsOnly()
        }
    }
}

spotless {
    fun com.diffplug.gradle.spotless.FormatExtension.setup() {
        endWithNewline()
        trimTrailingWhitespace()
    }
    kotlin {
        setup()
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        setup()
        ktlint(libs.versions.ktlint.get())
    }
}

kotlin {
    explicitApi()

    jvm {
        withJava()
    }

    js(IR) {
        browser {
            binaries.executable()
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }

        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.html)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.bundles.ktor.server)
                implementation(libs.bundles.ktor.client)

                implementation(libs.adventure.minimessage)
                implementation(libs.adventure.text.serializer.gson)
                implementation(libs.cache4k)
                implementation(libs.logback.classic)
            }
        }
    }
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

distributions {
    main {
        contents {
            from("$buildDir/libs") {
                rename("${rootProject.name}-jvm", rootProject.name)
                into("lib")
            }
        }
    }
}

tasks.named<Jar>("jvmJar") {
    val webpackTask = if (isDevelopment()) {
        "jsBrowserDevelopmentWebpack"
    } else {
        "jsBrowserProductionWebpack"
    }.let { taskName ->
        tasks.getByName<KotlinWebpack>(taskName)
    }

    dependsOn("check", webpackTask)
    from(File(webpackTask.destinationDirectory, webpackTask.outputFileName))
    rootProject.indraGit.applyVcsInformationToManifest(manifest)
}

tasks.named<JavaExec>("run") {
    if (isDevelopment()) {
        jvmArgs("-Dio.ktor.development=true")
    }

    classpath(tasks.getByName<Jar>("jvmJar"))
}

tasks.named<AbstractCopyTask>("jvmProcessResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    filesMatching("application.conf") {
        expand(
            "jsScriptFile" to "${rootProject.name}.js",
            "miniMessageVersion" to libs.adventure.minimessage.get().versionConstraint.requiredVersion,
            "commitHash" to rootProject.extensions.findByType<IndraGitExtension>()!!.commit()?.name.orEmpty(),
        )
    }
}

// Implicit task dependency issue?
tasks.distTar {
    dependsOn("allMetadataJar")
}

tasks.distZip {
    dependsOn("allMetadataJar")
}

/** Checks if the development property is set. */
fun isDevelopment(): Boolean = project.hasProperty("isDevelopment")

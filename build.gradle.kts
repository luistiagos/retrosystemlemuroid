import com.android.build.gradle.BaseExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(deps.plugins.android)
        classpath(deps.plugins.navigationSafeArgs)
        classpath(deps.plugins.kotlinGradlePlugin)
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version deps.versions.kotlin
    id("com.github.ben-manes.versions") version "0.51.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.4.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    id("com.android.test") version "8.7.1" apply false
    id("org.jetbrains.kotlin.android") version deps.versions.kotlin apply false
    id("androidx.baselineprofile") version "1.2.4" apply false
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version deps.versions.kotlin apply false
}

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }

    configurations.all {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "com.google.android.gms" -> useVersion(deps.versions.gms)
                "org.jetbrains.kotlin" -> {
                    if (requested.name.startsWith("kotlin-stdlib-jre")) {
                        with(requested) {
                            useTarget("$group:${name.replace("jre", "jdk")}:$version")
                        }
                    }
                    useVersion(deps.versions.kotlin)
                }
            }
        }
    }
}

subprojects {
    afterEvaluate {
        if (hasProperty("android")) {
            // BaseExtension is common parent for application, library and test modules
            apply(plugin = "org.jlleitschuh.gradle.ktlint")

            // Passivo de estilo congelado por modulo. O ktlint 1.x roda no sabor `ktlint_official`
            // (default quando o .editorconfig nao diz outro) e acusa ~900 violacoes so em
            // lemuroid-app; nenhuma e bug, mas a task falhava sempre e por isso ninguem a rodava.
            // Com baseline, `./gradlew ktlintCheck` passa e volta a falhar em violacao NOVA.
            // Regenerar com `./gradlew ktlintGenerateBaseline` -- e so depois de corrigir algo,
            // nunca para calar um apontamento recem-criado.
            extensions.configure(KtlintExtension::class.java) {
                baseline.set(file("config/ktlint/baseline.xml"))
            }

            extensions.configure(BaseExtension::class.java) {
                compileSdkVersion(deps.android.compileSdkVersion)
                buildToolsVersion(deps.android.buildToolsVersion)
                defaultConfig {
                    minSdkVersion(deps.android.minSdkVersion)
                    targetSdkVersion(deps.android.targetSdkVersion)
                    multiDexEnabled = true
                }
                lintOptions {
                    isAbortOnError = true
                    disable("UnusedResources") // https://issuetracker.google.com/issues/63150366
                    disable("InvalidPackage")
                    disable("VectorPath")
                    disable("TrustAllX509TrustManager")
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
    }

    configurations {
        all {
            exclude(group = "com.google.code.findbugs", module = "jsr305")
        }
    }
}

tasks {
    "clean"(Delete::class) {
        delete(buildDir)
    }
}

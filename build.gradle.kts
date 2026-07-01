import com.android.build.gradle.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {

    val cloudstreamGradlePluginVersion = project
        .findProperty("cloudstream.gradle.plugin.version")
        ?.toString()
        ?: "master-SNAPSHOT"

    val kotlinVersion = project
        .findProperty("kotlin.version")
        ?.toString()
        ?: "2.3.0"

    val androidGradlePluginVersion = project
        .findProperty("android.gradle.plugin.version")
        ?.toString()
        ?: "9.1.0"

    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:$androidGradlePluginVersion")
        classpath("com.github.recloudstream:gradle:$cloudstreamGradlePluginVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

val cloudstreamApiVersion = providers.gradleProperty("cloudstream.api.version").orElse("pre-release").get()
val kotlinxCoroutinesVersion = providers.gradleProperty("kotlinx.coroutines.version").orElse("1.10.1").get()
val kotlinxSerializationVersion = providers.gradleProperty("kotlinx.serialization.version").orElse("1.7.3").get()
val androidCompileSdkVersion = providers.gradleProperty("android.compileSdk.version").orElse("35").get().toInt()
val androidTargetSdkVersion = providers.gradleProperty("android.targetSdk.version").orElse(androidCompileSdkVersion.toString()).get().toInt()

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()
fun Project.android(configuration: LibraryExtension.() -> Unit) = extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/sad25kag/BetbetMiro-Extension")
        authors = listOf("sad25kag")
    }

    android {
        namespace = "com.sad25kag"
        defaultConfig {
            minSdk = 21
            compileSdkVersion(androidCompileSdkVersion)
            targetSdk = androidTargetSdkVersion
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions")
            }
        }
    }

    dependencies {
        add("cloudstream", "com.lagradost:cloudstream3:$cloudstreamApiVersion")
        add("implementation", kotlin("stdlib"))
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinxCoroutinesVersion")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.18")
        add("implementation", "com.squareup.okhttp3:okhttp:4.12.0")
        add("implementation", "org.jsoup:jsoup:1.22.1")
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        add("implementation", "com.fasterxml.jackson.core:jackson-databind:2.13.1")
        add("implementation", "com.google.code.gson:gson:2.11.0")
        add("implementation", "com.faendir.rhino:rhino-android:1.6.0")
        add("implementation", "app.cash.quickjs:quickjs-android:0.9.2")
        add("implementation", "me.xdrop:fuzzywuzzy:1.4.0")
        add("implementation", "androidx.core:core-ktx:1.18.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

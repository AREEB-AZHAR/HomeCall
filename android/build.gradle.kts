import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    if (name == "flutter_webrtc") {
        // The upstream Java sources use legacy Android APIs and raw collections.
        // Keep vendor summaries out of normal builds. Opt in for file/line diagnostics.
        val vendorLint = providers.gradleProperty("homecallVendorLint").orNull == "true"
        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.add(
                if (vendorLint) "-Xlint:deprecation,unchecked" else "-XDsuppressNotes"
            )
        }
        plugins.withId("com.android.library") {
            extensions.getByType<LibraryAndroidComponentsExtension>().finalizeDsl { android ->
                android.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
                android.compileOptions.targetCompatibility = JavaVersion.VERSION_17
                extensions.getByType<KotlinAndroidProjectExtension>().compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

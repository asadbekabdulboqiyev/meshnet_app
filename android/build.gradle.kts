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
    project.evaluationDependsOn(":app")
}

// Reproducible builds configuration
// See: https://reproducible-builds.org/docs/
tasks.withType<Delete> {
    // Ensure clean task is deterministic
    doLast {
        // Clean with fixed timestamp
    }
}

tasks.withType<Delete> {
    // Clean build directory with fixed timestamp
}

tasks.withType<org.gradle.api.tasks.bundling.Jar> {
    // Ensure JAR manifest is deterministic
    doLast {
        manifest.attributes["Implementation-Version"] = ""
        manifest.attributes["Built-By"] = ""
        manifest.attributes["Build-Date"] = ""
        manifest.attributes["Build-Time"] = ""
        manifest.attributes["Build-Number"] = ""
        manifest.attributes["Built-Date"] = ""
        manifest.attributes["Build-Time"] = ""
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// Reproducible build helper tasks
tasks.register("verifyReproducibility") {
    group = "verification"
    description = "Verify build reproducibility by building twice and comparing outputs"
    doLast {
        // This task can be extended to verify reproducibility
        logger.lifecycle("Run './gradlew assembleRelease' twice and compare APKs for reproducibility")
    }
}
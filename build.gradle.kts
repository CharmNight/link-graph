import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

intellijPlatform {
    pluginConfiguration {
        id = "com.charmnight.linkgraph"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        description = "Link graph plugin bootstrap."
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild")
        }
        vendor {
            name = "CharmNight"
        }
    }
}

val buildFrontend by tasks.registering {
    group = "build"
    description = "Build frontend assets when frontend/package.json exists."
    onlyIf { file("frontend/package.json").exists() }
    doLast {
        exec {
            workingDir = file("frontend")
            commandLine("npm", "ci")
        }
        exec {
            workingDir = file("frontend")
            commandLine("npm", "run", "build")
        }
    }
}

tasks {
    processResources {
        dependsOn(buildFrontend)
    }
}

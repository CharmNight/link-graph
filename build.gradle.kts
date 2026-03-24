import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.testing.Test

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
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

sourceSets {
    named("test") {
        java.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
    }
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

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests."
    group = "verification"
    dependsOn(
        tasks.named("testClasses"),
        tasks.named("prepareTestSandbox"),
        tasks.named("prepareTest"),
    )
    val baseTestTask = tasks.named<Test>("test").get()
    val testSourceSet = sourceSets.named("test").get()
    val mainSourceSet = sourceSets.named("main").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = baseTestTask.classpath + mainSourceSet.output
    jvmArgumentProviders.addAll(baseTestTask.jvmArgumentProviders)
    systemProperties.putAll(baseTestTask.systemProperties)
    environment(baseTestTask.environment)
    workingDir = baseTestTask.workingDir
    javaLauncher.set(baseTestTask.javaLauncher)
    minHeapSize = baseTestTask.minHeapSize
    maxHeapSize = baseTestTask.maxHeapSize
    useJUnit()
    include("**/*IT.class")
    shouldRunAfter(tasks.named("test"))
}

val buildFrontend by tasks.registering {
    group = "build"
    description = "Build frontend assets when web/package.json exists."
    onlyIf { file("web/package.json").exists() }
    doLast {
        exec {
            workingDir = file("web")
            commandLine("npm", "ci")
        }
        exec {
            workingDir = file("web")
            commandLine("npm", "run", "build")
        }
    }
}

tasks {
    test {
        exclude("**/*IT.class")
    }

    check {
        dependsOn(integrationTest)
    }

    processResources {
        dependsOn(buildFrontend)
    }
}

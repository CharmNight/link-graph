import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Sync

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
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    java.srcDir("src/integrationTest/kotlin")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations["testImplementation"],
)
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations["testRuntimeOnly"],
)
configurations[integrationTestSourceSet.compileOnlyConfigurationName].extendsFrom(
    configurations["testCompileOnly"],
)

intellijPlatform {
    pluginConfiguration {
        id = "com.charmnight.linkgraph"
        name = "链路图"
        version = providers.gradleProperty("pluginVersion")
        description = "链路图插件：可视化方法与资源链路，支持 Mermaid 导入、导出、对比，并可基于当前链路生成可审计的代码草稿。"
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
        tasks.named(integrationTestSourceSet.classesTaskName),
        tasks.named("prepareTestSandbox"),
        tasks.named("prepareTest"),
    )
    val baseTestTask = tasks.named<Test>("test").get()
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = baseTestTask.classpath + integrationTestSourceSet.output
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

val webDir = layout.projectDirectory.dir("web")
val webPackageJson = webDir.file("package.json")
val webPackageLock = webDir.file("package-lock.json")
val frontendTestMarker = layout.buildDirectory.file("frontend/test/last-success.txt")
val generatedFrontendResourcesDir = layout.buildDirectory.dir("generated/frontend-resources/main")

val frontendInputs = files(
    webPackageJson,
    webPackageLock,
    webDir.file("vite.config.ts"),
    webDir.file("tsconfig.json"),
    webDir.file("index.html"),
    fileTree(webDir.dir("src")),
)

val frontendInstall by tasks.registering {
    group = "frontend"
    description = "Install frontend dependencies."
    onlyIf { webPackageJson.asFile.exists() }
    inputs.files(webPackageJson, webPackageLock)
    outputs.dir(webDir.dir("node_modules"))
    doLast {
        exec {
            workingDir = webDir.asFile
            commandLine("npm", "ci")
        }
    }
}

val frontendTest by tasks.registering {
    group = "verification"
    description = "Run frontend tests."
    dependsOn(frontendInstall)
    onlyIf { webPackageJson.asFile.exists() }
    inputs.files(frontendInputs)
    outputs.file(frontendTestMarker)
    doLast {
        exec {
            workingDir = webDir.asFile
            commandLine("npm", "test")
        }
        val marker = frontendTestMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText("frontend tests passed\n")
    }
}

val frontendBuild by tasks.registering {
    group = "build"
    description = "Build frontend assets."
    dependsOn(frontendInstall)
    onlyIf { webPackageJson.asFile.exists() }
    inputs.files(frontendInputs)
    outputs.dir(webDir.dir("dist"))
    doLast {
        exec {
            workingDir = webDir.asFile
            commandLine("npm", "run", "build")
        }
    }
}

val frontendPackResources by tasks.registering(Sync::class) {
    group = "build"
    description = "Copy built frontend assets into generated plugin resources."
    dependsOn(frontendBuild)
    from(webDir.dir("dist"))
    into(generatedFrontendResourcesDir.map { it.dir("linkgraph") })
}

tasks {
    runIde {
        jvmArgs("-Didea.auto.reload.plugins=false")
    }

    test {
        exclude("**/*IT.class")
    }

    check {
        dependsOn(integrationTest)
        dependsOn(frontendTest)
    }

    processResources {
        mustRunAfter(frontendPackResources)
        from(generatedFrontendResourcesDir)
    }

    named("prepareSandbox") {
        dependsOn(frontendPackResources)
    }
}

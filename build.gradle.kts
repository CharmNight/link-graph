import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Sync
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

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
    implementation("com.google.code.gson:gson:2.11.0")

    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        javaCompiler(providers.gradleProperty("platformBuildNumber"))
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

kotlin {
    target.compilations.named(integrationTestSourceSet.name) {
        associateWith(target.compilations.getByName("main"))
    }
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
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        description = "Link Graph is an IntelliJ Platform plugin for exploring source relationships, project architecture, class diagrams, and review impact graphs inside a project. It provides a JCEF-based graph workbench with Mermaid import/export, source navigation, local fallback workflows, and optional remote LLM-assisted question answering, implementation suggestions, and code diff workflows."
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild")
            untilBuild = provider { null }
        }
        vendor {
            name = "CharmNight"
        }
    }

    pluginVerification {
        ides {
            ide(
                providers.gradleProperty("platformType"),
                providers.gradleProperty("platformVersion"),
            )
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

val runIdeSandboxOptionsFile = layout.buildDirectory.file(
    providers.zip(
        providers.gradleProperty("platformType"),
        providers.gradleProperty("platformVersion"),
    ) { platformType, platformVersion ->
        "idea-sandbox/$platformType-$platformVersion/config/options/other.xml"
    },
)

val sanitizeRunIdeSandbox by tasks.registering {
    group = "intellij platform"
    description = "Removes stale IDE compatibility metadata that can break the runIde sandbox."
    mustRunAfter(tasks.named("prepareSandbox"))

    doLast {
        val optionsFile = runIdeSandboxOptionsFile.get().asFile
        if (!optionsFile.isFile) {
            return@doLast
        }

        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(optionsFile)
        val components = document.getElementsByTagName("component")
        val staleComponents = (0 until components.length)
            .map { components.item(it) }
            .filter { node ->
                node.attributes?.getNamedItem("name")?.nodeValue == "GradleJvmSupportMatrix"
            }

        if (staleComponents.isEmpty()) {
            return@doLast
        }

        staleComponents.forEach { node ->
            node.parentNode.removeChild(node)
        }
        TransformerFactory.newInstance()
            .newTransformer()
            .apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
            }
            .transform(DOMSource(document), StreamResult(optionsFile))
    }
}

tasks {
    runIde {
        dependsOn(sanitizeRunIdeSandbox)
        jvmArgs(
            "-Didea.auto.reload.plugins=false",
            "-Dide.no.platform.update=true",
            "-Dgradle.compatibility.update.interval=0",
            "-Dexternal.system.auto.import.disabled=true",
        )
    }

    test {
        exclude("**/*IT.class")
    }

    check {
        dependsOn(integrationTest)
        dependsOn(frontendTest)
    }

    processResources {
        dependsOn(frontendPackResources)
        from(generatedFrontendResourcesDir)
    }

    named("prepareSandbox") {
        dependsOn(frontendPackResources)
    }

    named("buildSearchableOptions") {
        enabled = false
    }
}

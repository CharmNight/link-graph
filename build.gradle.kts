import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Sync
import java.io.ByteArrayOutputStream
import java.io.File
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
val frontendTransportContract = layout.projectDirectory.file("protocol/graph-editor-transport-contract.json")
val frontendTestMarker = layout.buildDirectory.file("frontend/test/last-success.txt")
val generatedFrontendResourcesDir = layout.buildDirectory.dir("generated/frontend-resources/main")

val frontendInputs = files(
    webPackageJson,
    webPackageLock,
    frontendTransportContract,
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
val runIdeSandboxJdkTableFile = layout.buildDirectory.file(
    providers.zip(
        providers.gradleProperty("platformType"),
        providers.gradleProperty("platformVersion"),
    ) { platformType, platformVersion ->
        "idea-sandbox/$platformType-$platformVersion/config/options/jdk.table.xml"
    },
)
val runIdeProjectJdkName = providers.gradleProperty("javaVersion").map { "zulu-$it" }
val runIdeSandboxJavaVersions = providers.gradleProperty("runIdeSandboxJavaVersions")
    .orElse("11,17,21")
    .map { versions ->
        versions
            .split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
val runIdeSandboxRootDir = layout.buildDirectory.dir(
    providers.zip(
        providers.gradleProperty("platformType"),
        providers.gradleProperty("platformVersion"),
    ) { platformType, platformVersion ->
        "idea-sandbox/$platformType-$platformVersion"
    },
)

fun File.toIdeaUrlPath(): String = absolutePath.replace(File.separatorChar, '/')

fun majorJavaVersion(javaVersion: String): String {
    val normalized = javaVersion.trim().trim('"')
    return if (normalized.startsWith("1.")) {
        normalized.substringAfter("1.").substringBefore('.').substringBefore('_')
    } else {
        normalized.substringBefore('.').substringBefore('_')
    }
}

fun javaVersionFromReleaseFile(jdkHome: File): String {
    val releaseFile = jdkHome.resolve("release")
    val releaseVersion = releaseFile
        .takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
        ?.substringAfter('=')
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }

    return releaseVersion ?: providers.gradleProperty("javaVersion").get()
}

fun installedJdkHomes(): List<File> {
    val homes = linkedSetOf<File>()

    listOf(
        System.getenv("JAVA_HOME").orEmpty(),
        System.getProperty("java.home").orEmpty(),
    ).filter { it.isNotBlank() }
        .map { file(it) }
        .forEach { homes.add(it) }

    if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        file("/Library/Java/JavaVirtualMachines")
            .listFiles()
            .orEmpty()
            .map { it.resolve("Contents/Home") }
            .forEach { homes.add(it) }
    }

    return homes.filter { it.isDirectory && it.resolve("release").isFile }
}

fun resolveInstalledJdkHome(javaVersion: String): File? {
    if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        val output = ByteArrayOutputStream()
        val result = exec {
            commandLine("/usr/libexec/java_home", "-v", javaVersion)
            standardOutput = output
            isIgnoreExitValue = true
        }
        val detectedHome = output.toString().trim()
        if (result.exitValue == 0 && detectedHome.isNotBlank()) {
            return file(detectedHome)
        }
    }

    val requestedMajor = majorJavaVersion(javaVersion)
    return installedJdkHomes().firstOrNull { jdkHome ->
        majorJavaVersion(javaVersionFromReleaseFile(jdkHome)) == requestedMajor
    }
}

fun resolveRunIdeJdkHome(javaVersion: String): File {
    val configuredHome = providers.gradleProperty("runIdeJdkHome").orNull
        ?: providers.environmentVariable("RUNIDE_JDK_HOME").orNull
    if (!configuredHome.isNullOrBlank()) {
        return file(configuredHome)
    }

    if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        val output = ByteArrayOutputStream()
        val result = exec {
            commandLine("/usr/libexec/java_home", "-v", javaVersion)
            standardOutput = output
            isIgnoreExitValue = true
        }
        val detectedHome = output.toString().trim()
        if (result.exitValue == 0 && detectedHome.isNotBlank()) {
            return file(detectedHome)
        }
    }

    val javaHome = System.getenv("JAVA_HOME").orEmpty()
    if (javaHome.isNotBlank()) {
        return file(javaHome)
    }

    return file(System.getProperty("java.home"))
}

fun jdkInstallationName(jdkHome: File): String? =
    jdkHome.parentFile?.parentFile?.name
        ?.removeSuffix(".jdk")
        ?.takeIf { it.isNotBlank() && it != "Contents" }

fun runIdeSandboxJdkEntries(): Map<String, File> {
    val entries = linkedMapOf<String, File>()
    val pluginJavaVersion = providers.gradleProperty("javaVersion").get()
    val pluginJdkHome = resolveRunIdeJdkHome(pluginJavaVersion)
    val requestedMajorVersions = runIdeSandboxJavaVersions.get()
        .map { majorJavaVersion(it) }
        .toSet()

    fun addAlias(name: String?, jdkHome: File) {
        if (!name.isNullOrBlank() && jdkHome.isDirectory) {
            entries.putIfAbsent(name, jdkHome)
        }
    }

    addAlias(runIdeProjectJdkName.get(), pluginJdkHome)
    addAlias(majorJavaVersion(javaVersionFromReleaseFile(pluginJdkHome)), pluginJdkHome)
    addAlias(jdkInstallationName(pluginJdkHome), pluginJdkHome)

    runIdeSandboxJavaVersions.get().forEach { javaVersion ->
        val jdkHome = if (majorJavaVersion(javaVersion) == majorJavaVersion(javaVersionFromReleaseFile(pluginJdkHome))) {
            pluginJdkHome
        } else {
            resolveInstalledJdkHome(javaVersion)
        }

        if (jdkHome == null) {
            logger.warn("No installed JDK $javaVersion found for the runIde sandbox SDK table.")
            return@forEach
        }

        addAlias(javaVersion, jdkHome)
        addAlias(majorJavaVersion(javaVersionFromReleaseFile(jdkHome)), jdkHome)
        addAlias(jdkInstallationName(jdkHome), jdkHome)
    }

    installedJdkHomes()
        .filter { jdkHome ->
            majorJavaVersion(javaVersionFromReleaseFile(jdkHome)) in requestedMajorVersions
        }
        .forEach { jdkHome ->
            addAlias(jdkInstallationName(jdkHome), jdkHome)
        }

    return entries
}

fun writeRunIdeJdkTable(jdkTableFile: File, jdkEntries: Map<String, File>) {
    require(jdkEntries.isNotEmpty()) {
        "No runIde JDK entries were resolved."
    }

    val document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .newDocument()
    val application = document.createElement("application")
    val table = document.createElement("component").apply {
        setAttribute("name", "ProjectJdkTable")
    }

    fun appendOption(parent: org.w3c.dom.Element, name: String, value: String) {
        parent.appendChild(document.createElement(name).apply {
            setAttribute("value", value)
        })
    }

    fun appendCompositeRoot(parent: org.w3c.dom.Element, pathElementName: String, urls: Iterable<String>) {
        val pathElement = document.createElement(pathElementName)
        val composite = document.createElement("root").apply {
            setAttribute("type", "composite")
        }
        urls.forEach { url ->
            composite.appendChild(document.createElement("root").apply {
                setAttribute("url", url)
                setAttribute("type", "simple")
            })
        }
        pathElement.appendChild(composite)
        parent.appendChild(pathElement)
    }

    fun appendJdk(jdkName: String, jdkHome: File) {
        require(jdkHome.isDirectory) {
            "runIde JDK home does not exist: ${jdkHome.absolutePath}"
        }

        val javaVersion = javaVersionFromReleaseFile(jdkHome)
        val jdk = document.createElement("jdk").apply {
            setAttribute("version", "2")
        }

        appendOption(jdk, "name", jdkName)
        appendOption(jdk, "type", "JavaSDK")
        appendOption(jdk, "version", "java version \"$javaVersion\"")
        appendOption(jdk, "homePath", jdkHome.toIdeaUrlPath())

        val roots = document.createElement("roots")
        appendCompositeRoot(
            roots,
            "annotationsPath",
            listOf("jar://\$APPLICATION_HOME_DIR\$/plugins/java/lib/resources/jdkAnnotations.jar!/")
        )

        val jmods = jdkHome.resolve("jmods")
            .listFiles { file -> file.isFile && file.extension == "jmod" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()
        appendCompositeRoot(
            roots,
            "classPath",
            jmods.map { moduleName -> "jrt://${jdkHome.toIdeaUrlPath()}!/$moduleName" }
        )
        appendCompositeRoot(roots, "javadocPath", emptyList())

        val sourceZip = jdkHome.resolve("lib/src.zip")
        val sourceRoots = if (sourceZip.isFile) {
            jmods.map { moduleName -> "jar://${sourceZip.toIdeaUrlPath()}!/$moduleName" }
        } else {
            emptyList()
        }
        appendCompositeRoot(roots, "sourcePath", sourceRoots)

        jdk.appendChild(roots)
        table.appendChild(jdk)
    }

    jdkEntries.forEach { (jdkName, jdkHome) ->
        appendJdk(jdkName, jdkHome)
    }
    application.appendChild(table)
    document.appendChild(application)

    jdkTableFile.parentFile.mkdirs()
    TransformerFactory.newInstance()
        .newTransformer()
        .apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        .transform(DOMSource(document), StreamResult(jdkTableFile))
}

val sanitizeRunIdeSandbox by tasks.registering {
    group = "intellij platform"
    description = "Removes stale IDE compatibility and project state that can break the runIde sandbox."
    mustRunAfter(tasks.named("prepareSandbox"))

    doLast {
        val sandboxRoot = runIdeSandboxRootDir.get().asFile
        delete(
            sandboxRoot.resolve("config/workspace"),
            sandboxRoot.resolve("system/projects"),
        )

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

val prepareRunIdeSandboxSdk by tasks.registering {
    group = "intellij platform"
    description = "Preconfigures the runIde sandbox with the project Java SDK."
    dependsOn(tasks.named("prepareSandbox"))
    dependsOn(sanitizeRunIdeSandbox)

    inputs.property("javaVersion", providers.gradleProperty("javaVersion"))
    inputs.property("jdkName", runIdeProjectJdkName)
    inputs.property("sandboxJavaVersions", runIdeSandboxJavaVersions.map { it.joinToString(",") })
    outputs.file(runIdeSandboxJdkTableFile)

    doLast {
        val jdkEntries = runIdeSandboxJdkEntries()
        writeRunIdeJdkTable(
            jdkTableFile = runIdeSandboxJdkTableFile.get().asFile,
            jdkEntries = jdkEntries,
        )
        logger.lifecycle(
            "Preconfigured runIde sandbox SDKs: " +
                jdkEntries.entries.joinToString { (jdkName, jdkHome) -> "$jdkName=${jdkHome.absolutePath}" },
        )
    }
}

tasks {
    runIde {
        dependsOn(prepareRunIdeSandboxSdk)
        jvmArgs(
            "-Xms512m",
            "-Xmx3072m",
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

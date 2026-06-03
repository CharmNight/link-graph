package com.charmnight.linkgraph.source

import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.jar.JarFile

internal object ClassFileSourceDecompiler {
    data class Result(
        val text: String?,
        val diagnostic: String? = null,
    )

    fun decompileVirtualFileWithDiagnostic(file: VirtualFile): Result {
        if (!file.extension.equals("class", ignoreCase = true)) {
            return Result(null, "NOT_CLASS_FILE")
        }
        val text = runCatching {
            val decompiler = Class.forName("com.intellij.psi.impl.compiled.ClassFileDecompiler")
                .getDeclaredConstructor()
                .newInstance()
            val method = decompiler.javaClass.getMethod("decompile", VirtualFile::class.java)
            (method.invoke(decompiler, file) as? CharSequence)?.toString()
        }.getOrNull()
        if (text == null) {
            return Result(null, "IDEA_CLASS_DECOMPILER_UNAVAILABLE")
        }
        if (!looksLikeDecompiledJava(text)) {
            return Result(null, "IDEA_CLASS_DECOMPILER_RETURNED_NON_JAVA")
        }
        return Result(text)
    }

    fun decompileVirtualFile(file: VirtualFile): String? {
        return decompileVirtualFileWithDiagnostic(file).text
    }

    fun decompileJarEntryWithDiagnostic(
        jarPath: Path,
        classEntryName: String,
    ): Result {
        val normalizedJar = jarPath.normalize().takeIf(Files::isRegularFile)
            ?: return Result(null, "CLASS_JAR_NOT_FOUND")
        val virtualFile = runCatching {
            StandardFileSystems.jar().let { jarFileSystem ->
                jarFileSystem.refreshAndFindFileByPath("${normalizedJar}!/$classEntryName")
                    ?: jarFileSystem.findFileByPath("${normalizedJar}!/$classEntryName")
                    ?: jarFileSystem.refreshAndFindFileByPath("jar://${normalizedJar}!/$classEntryName")
                    ?: jarFileSystem.findFileByPath("jar://${normalizedJar}!/$classEntryName")
            }
        }.getOrNull()
        if (virtualFile != null) {
            val idea = decompileVirtualFileWithDiagnostic(virtualFile)
            if (idea.text != null) {
                return idea
            }
        }
        return decompileWithFernflower(normalizedJar, classEntryName)
    }

    fun decompileJarEntry(
        jarPath: Path,
        classEntryName: String,
    ): String? {
        return decompileJarEntryWithDiagnostic(jarPath, classEntryName).text
    }

    private fun decompileWithFernflower(
        inputJar: Path,
        classEntryName: String,
    ): Result {
        val decompilerJar = fernflowerJar()
            ?: return Result(null, "FERNFLOWER_JAR_NOT_FOUND")
        val outputDir = Files.createTempDirectory("link-graph-decompile")
        try {
            if (runFernflowerInProcess(inputJar, outputDir, decompilerJar) == true) {
                readFernflowerOutput(outputDir, inputJar.fileName.toString(), classEntryName)
                    ?.takeIf(::looksLikeDecompiledJava)
                    ?.let { text -> return Result(text) }
            }
        } finally {
            runCatching {
                Files.walk(outputDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(Files::deleteIfExists)
            }
        }
        val processOutputDir = Files.createTempDirectory("link-graph-decompile-process")
        return try {
            runFernflowerOutOfProcess(inputJar, processOutputDir, decompilerJar)
                ?: return Result(null, "FERNFLOWER_PROCESS_FAILED")
            val text = readFernflowerOutput(processOutputDir, inputJar.fileName.toString(), classEntryName)
                ?.takeIf(::looksLikeDecompiledJava)
            if (text == null) {
                Result(null, "FERNFLOWER_OUTPUT_MISSING")
            } else {
                Result(text)
            }
        } finally {
            runCatching {
                Files.walk(processOutputDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(Files::deleteIfExists)
            }
        }
    }

    private fun readFernflowerOutput(
        outputDir: Path,
        inputJarName: String,
        classEntryName: String,
    ): String? {
        val javaEntryName = classEntryName.removeSuffix(".class") + ".java"
        val exploded = outputDir.resolve(javaEntryName)
        if (Files.isRegularFile(exploded)) {
            return Files.readString(exploded)
        }
        val candidateJars = buildList {
            add(outputDir.resolve(inputJarName))
            addAll(Files.list(outputDir).use { stream ->
                stream.filter { path -> path.fileName.toString().endsWith(".jar") }.toList()
            })
        }.distinct()
        return candidateJars.firstNotNullOfOrNull { sourceJar ->
            readJarText(sourceJar, javaEntryName)
        }
    }

    private fun readJarText(
        jarPath: Path,
        entryName: String,
    ): String? {
        if (!Files.isRegularFile(jarPath)) {
            return null
        }
        return runCatching {
            JarFile(jarPath.toFile()).use { jar ->
                val entry = jar.getJarEntry(entryName) ?: return null
                jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
            }
        }.getOrNull()
    }

    private fun runFernflowerInProcess(inputJar: Path, outputDir: Path, decompilerJar: Path): Boolean? {
        return runCatching {
            URLClassLoader(arrayOf(decompilerJar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val main = loader.loadClass("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")
                    .getMethod("main", Array<String>::class.java)
                main.invoke(null, arrayOf("-log=ERROR", inputJar.toString(), outputDir.toString()) as Any)
            }
            true
        }.getOrNull()
    }

    private fun runFernflowerOutOfProcess(inputJar: Path, outputDir: Path, decompilerJar: Path): Boolean? {
        return runCatching {
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java")
                .takeIf(Files::isRegularFile)
                ?.toString()
                ?: "java"
            val exitCode = ProcessBuilder(
                javaExecutable,
                "-cp",
                decompilerJar.toString(),
                "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler",
                "-log=ERROR",
                inputJar.toString(),
                outputDir.toString(),
            )
                .redirectErrorStream(true)
                .start()
                .apply { inputStream.bufferedReader().use { reader -> reader.readText() } }
                .waitFor()
            exitCode == 0
        }.getOrNull()?.takeIf { it }
    }

    private fun fernflowerJar(): Path? {
        val classPathCandidates = System.getProperty("java.class.path").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .mapNotNull { entry -> runCatching { Path.of(entry).normalize() }.getOrNull() }
        val ideaHomeCandidates = listOfNotNull(
            System.getProperty("idea.home.path"),
            System.getProperty("idea.home"),
        ).mapNotNull { home ->
            runCatching { Path.of(home).resolve("plugins/java-decompiler/lib/java-decompiler.jar").normalize() }.getOrNull()
        }
        return (classPathCandidates + ideaHomeCandidates)
            .firstOrNull { path ->
                path.fileName?.toString() == "java-decompiler.jar" && Files.isRegularFile(path)
            }
            ?: gradleCacheFernflowerJar()
    }

    private fun gradleCacheFernflowerJar(): Path? {
        val userHome = System.getProperty("user.home")?.takeIf(String::isNotBlank) ?: return null
        val caches = runCatching { Path.of(userHome).resolve(".gradle/caches").normalize() }.getOrNull()
            ?.takeIf(Files::isDirectory)
            ?: return null
        return runCatching {
            Files.find(caches, 9, { path, attributes ->
                attributes.isRegularFile &&
                    path.fileName?.toString() == "java-decompiler.jar" &&
                    path.toString().contains("plugins/java-decompiler/lib")
            }).use { stream ->
                stream.findFirst().orElse(null)
            }
        }.getOrNull()
    }

    private fun looksLikeDecompiledJava(text: String): Boolean {
        if (text.isBlank() || text.contains("Decompiled class stub")) {
            return false
        }
        return listOf(" class ", " interface ", " enum ", " record ", "@interface ").any(text::contains)
    }
}

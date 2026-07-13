package com.charmnight.linkgraph.source

import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * 字节码反编译工具：通过 IntelliJ 内置 ClassFileDecompiler 反编译 .class 文件，
 * 或在外部 jar 场景下使用独立的 FernFlower 反编译器。
 */
internal object ClassFileSourceDecompiler {
    /** 反编译结果，包含文本或诊断信息。 */
    data class Result(
        val text: String?,
        val diagnostic: String? = null,
    )

    /** 反编译 IntelliJ 虚拟文件形式的 class 文件，附带诊断。 */
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

    /** 反编译 IntelliJ 虚拟文件形式的 class 文件，仅返回文本结果（无诊断时为空）。 */
    fun decompileVirtualFile(file: VirtualFile): String? {
        return decompileVirtualFileWithDiagnostic(file).text
    }

    /**
     * 反编译外部 jar 中的指定 class 条目，先尝试 IntelliJ 内置反编译器，
     * 失败时回落到独立 FernFlower 进程。
     *
     * @param jarPath 目标 jar 文件路径
     * @param classEntryName jar 内部 class 条目名（如 com/foo/Bar.class）
     * @return 反编译结果，可能包含 Java 源码或诊断信息
     */
    fun decompileJarEntryWithDiagnostic(
        jarPath: Path,
        classEntryName: String,
    ): Result {
        val normalizedJar = jarPath.normalize().takeIf(Files::isRegularFile)
            ?: return Result(null, "CLASS_JAR_NOT_FOUND")
        classEntryLimitFailure(normalizedJar, classEntryName)?.let { diagnostic ->
            return Result(null, diagnostic)
        }
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

    /** 反编译外部 jar 内的 class 条目，仅返回 Java 源码文本。 */
    fun decompileJarEntry(
        jarPath: Path,
        classEntryName: String,
    ): String? {
        return decompileJarEntryWithDiagnostic(jarPath, classEntryName).text
    }

    /** 使用独立 FernFlower 子进程反编译仅含目标类族的临时 jar。 */
    private fun decompileWithFernflower(
        inputJar: Path,
        classEntryName: String,
    ): Result {
        val decompilerJar = fernflowerJar()
            ?: return Result(null, "FERNFLOWER_JAR_NOT_FOUND")
        val workDir = Files.createTempDirectory("link-graph-decompile")
        return try {
            val boundedInputJar = workDir.resolve("input.jar")
            val outputDir = workDir.resolve("output")
            Files.createDirectories(outputDir)
            runCatching {
                createBoundedFernflowerInputJar(inputJar, classEntryName, boundedInputJar)
            }.getOrElse { error ->
                return Result(null, error.message ?: "FERNFLOWER_INPUT_INVALID")
            }
            runFernflowerOutOfProcess(boundedInputJar, outputDir, decompilerJar)
                ?: return Result(null, "FERNFLOWER_PROCESS_FAILED")
            val text = readFernflowerOutput(outputDir, boundedInputJar.fileName.toString(), classEntryName)
                ?.takeIf(::looksLikeDecompiledJava)
            if (text == null) {
                Result(null, "FERNFLOWER_OUTPUT_MISSING")
            } else {
                Result(text)
            }
        } finally {
            runCatching {
                Files.walk(workDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(Files::deleteIfExists)
            }
        }
    }

    /** 读取 FernFlower 反编译输出：优先读展开的 .java 文件，其次从产物 jar 中读取对应条目。 */
    private fun readFernflowerOutput(
        outputDir: Path,
        inputJarName: String,
        classEntryName: String,
    ): String? {
        val javaEntryName = classEntryName.removeSuffix(".class").substringBefore('$') + ".java"
        val exploded = outputDir.resolve(javaEntryName)
        if (Files.isRegularFile(exploded)) {
            return readFileTextBounded(exploded, SourceArchiveReadLimits.MAX_FERNFLOWER_OUTPUT_BYTES)
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

    /** 从指定 jar 中按条目名读取文本内容，读取失败或条目不存在时返回 null。 */
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
                jar.readEntryTextBounded(entry, SourceArchiveReadLimits.MAX_FERNFLOWER_OUTPUT_BYTES)
            }
        }.getOrNull()
    }

    /** 通过启动独立 Java 子进程运行 FernFlower，规避同进程类加载冲突。 */
    private fun runFernflowerOutOfProcess(inputJar: Path, outputDir: Path, decompilerJar: Path): Boolean? {
        return runCatching {
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java")
                .takeIf(Files::isRegularFile)
                ?.toString()
                ?: "java"
            val process = ProcessBuilder(
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
            val result = TimedProcessRunner.run(
                process = process,
                timeoutMillis = FERNFLOWER_PROCESS_TIMEOUT_MILLIS,
                maxOutputBytes = SourceArchiveReadLimits.MAX_FERNFLOWER_PROCESS_OUTPUT_BYTES,
            )
            !result.timedOut && !result.outputLimitExceeded && result.exitCode == 0
        }.getOrNull()?.takeIf { it }
    }

    /**
     * 构建 Fernflower 的最小输入归档：只复制目标外部类及其所有 `$` 嵌套类。
     * 条目数、单项大小和总字节数均有硬限制，任何超限都会拒绝整个反编译请求。
     */
    internal fun createBoundedFernflowerInputJar(
        sourceJar: Path,
        requestedClassEntryName: String,
        targetJar: Path,
    ): List<String> {
        require(requestedClassEntryName.endsWith(".class")) { "FERNFLOWER_INPUT_NOT_CLASS" }
        require(!requestedClassEntryName.startsWith('/') && ".." !in requestedClassEntryName.split('/')) {
            "FERNFLOWER_INPUT_ENTRY_INVALID"
        }
        val classBase = requestedClassEntryName.removeSuffix(".class").substringBefore('$')
        val outerEntryName = "$classBase.class"
        val nestedPrefix = "$classBase\$"
        return JarFile(sourceJar.toFile()).use { jar ->
            val selected = jar.entries().asSequence()
                .filterNot(JarEntry::isDirectory)
                .filter { entry ->
                    entry.name == outerEntryName ||
                        (entry.name.startsWith(nestedPrefix) && entry.name.endsWith(".class"))
                }
                .sortedWith(
                    compareBy<JarEntry>(
                        { entry -> entry.name.count { character -> character == '$' } },
                        JarEntry::getName,
                    ),
                )
                .toList()
            require(selected.any { entry -> entry.name == requestedClassEntryName }) {
                "CLASS_JAR_ENTRY_NOT_FOUND"
            }
            require(selected.size <= SourceArchiveReadLimits.MAX_FERNFLOWER_INPUT_ENTRIES) {
                "FERNFLOWER_INPUT_ENTRY_LIMIT"
            }
            var totalBytes = 0L
            val copied = mutableListOf<String>()
            targetJar.parent?.let(Files::createDirectories)
            JarOutputStream(Files.newOutputStream(targetJar)).use { output ->
                selected.forEach { entry ->
                    val bytes = jar.readEntryBytesBounded(entry, SourceArchiveReadLimits.MAX_CLASS_ENTRY_BYTES)
                        ?: error("FERNFLOWER_INPUT_CLASS_TOO_LARGE")
                    totalBytes += bytes.size
                    require(totalBytes <= SourceArchiveReadLimits.MAX_FERNFLOWER_INPUT_BYTES) {
                        "FERNFLOWER_INPUT_BYTE_LIMIT"
                    }
                    output.putNextEntry(JarEntry(entry.name))
                    output.write(bytes)
                    output.closeEntry()
                    copied += entry.name
                }
            }
            copied
        }
    }

    /** 校验反编译目标 class 条目存在且不超过大小上限。 */
    private fun classEntryLimitFailure(jarPath: Path, entryName: String): String? =
        runCatching {
            JarFile(jarPath.toFile()).use { jar ->
                val entry = jar.getJarEntry(entryName) ?: return@use "CLASS_JAR_ENTRY_NOT_FOUND"
                if (entry.isDirectory) {
                    return@use "CLASS_JAR_ENTRY_NOT_FOUND"
                }
                if (jar.isEntryWithinLimit(entry, SourceArchiveReadLimits.MAX_CLASS_ENTRY_BYTES)) {
                    null
                } else {
                    "JAR_ENTRY_TOO_LARGE"
                }
            }
        }.getOrElse { error -> error.message ?: error.javaClass.simpleName }

    /** 查找 FernFlower 反编译 jar：依次搜索 classpath、IDE 安装目录，最后回落到 Gradle 缓存。 */
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

    /** 在用户 Gradle 缓存目录下递归查找 java-decompiler.jar，作为最后兜底来源。 */
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

    /** 粗略判断反编译结果是否为有效 Java 源码：排除空文本与桩代码，并要求包含类/接口等关键字。 */
    private fun looksLikeDecompiledJava(text: String): Boolean {
        if (text.isBlank() || text.contains("Decompiled class stub")) {
            return false
        }
        return listOf(" class ", " interface ", " enum ", " record ", "@interface ").any(text::contains)
    }

    private const val FERNFLOWER_PROCESS_TIMEOUT_MILLIS: Long = 30_000
}

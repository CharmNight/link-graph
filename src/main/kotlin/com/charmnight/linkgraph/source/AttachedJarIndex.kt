package com.charmnight.linkgraph.source

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

/** 附加 jar 中类条目的种类。 */
enum class AttachedJarClassKind {
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION,
    RECORD,
}

/** 附加 jar 的指纹信息，用于缓存键等场景。 */
data class AttachedJarEntryFingerprint(
    val path: String,
    val sourceJarPath: String?,
    val classJarLastModifiedMillis: Long,
    val classJarSize: Long,
    val classJarSha256: String,
    val sourceJarLastModifiedMillis: Long?,
    val sourceJarSize: Long?,
    val sourceJarSha256: String?,
)

/** 附加 jar 中类条目，包含源码/class 条目路径、种类、父类与字段方法等。 */
data class AttachedJarClassEntry(
    val qualifiedName: String,
    val classEntryName: String?,
    val sourceEntryName: String?,
    val classJarPath: String,
    val sourceJarPath: String?,
    val kind: AttachedJarClassKind = AttachedJarClassKind.CLASS,
    val superClassName: String? = null,
    val interfaceNames: List<String> = emptyList(),
    val fields: List<AttachedJarFieldEntry> = emptyList(),
    val methods: List<AttachedJarMethodEntry> = emptyList(),
) {
    val displayPath: String =
        sourceEntryName?.let { sourceEntry -> sourceJarPath?.let { sourceJar -> "$sourceJar!/$sourceEntry" } }
            ?: "$classJarPath!/$classEntryName"
}

/** 字段条目（来自字节码）。 */
data class AttachedJarFieldEntry(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
)

/** 方法条目（来自字节码）。 */
data class AttachedJarMethodEntry(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
)

/** 附加 jar 中的 SPI 服务文件条目。 */
data class AttachedJarServiceFileEntry(
    val serviceInterfaceName: String,
    val providerClassNames: List<String>,
    val resourceEntryName: String,
    val jarPath: String,
    val sourceJarPath: String?,
    val resourceJarPath: String = sourceJarPath ?: jarPath,
    val origin: SourceOrigin = if (sourceJarPath != null && resourceJarPath == sourceJarPath) {
        SourceOrigin.USER_ATTACHED_SOURCE_JAR
    } else {
        SourceOrigin.USER_ATTACHED_CLASS_JAR
    },
)

/** 附加 jar 索引，按类限定名与 SPI 服务接口聚合条目。 */
class AttachedJarIndex(
    val classCandidatesByQualifiedName: Map<String, List<AttachedJarClassEntry>> = emptyMap(),
    val serviceFilesByInterfaceName: Map<String, List<AttachedJarServiceFileEntry>> = emptyMap(),
    val fingerprints: List<AttachedJarEntryFingerprint> = emptyList(),
) {
    /** 按附件配置顺序展开的全部类候选；重复限定名不会互相覆盖。 */
    val classEntries: List<AttachedJarClassEntry> = classCandidatesByQualifiedName.values.flatten()

    /** 按限定名查找类条目。 */
    fun findClass(qualifiedName: String): AttachedJarClassEntry? =
        findClassCandidates(qualifiedName).firstOrNull()

    /** 按附件配置顺序返回同一限定名的全部候选。 */
    fun findClassCandidates(qualifiedName: String): List<AttachedJarClassEntry> =
        classCandidatesByQualifiedName[qualifiedName.trim()].orEmpty()

    /** 查询指定 SPI 接口的所有服务文件。 */
    fun serviceFiles(interfaceName: String): List<AttachedJarServiceFileEntry> =
        serviceFilesByInterfaceName[interfaceName].orEmpty()

    companion object {
        /** 根据附加 jar 条目列表构建索引。 */
        fun build(entries: List<AttachedJarEntry>): AttachedJarIndex =
            AttachedJarIndexBuilder().build(entries)
    }
}

/** 附加 jar 索引构建器，解析 class jar 与 source jar 生成索引。 */
class AttachedJarIndexBuilder {
    /** 构建索引主入口。 */
    fun build(entries: List<AttachedJarEntry>): AttachedJarIndex {
        val classCandidates = linkedMapOf<String, MutableList<AttachedJarClassEntry>>()
        val serviceFiles = linkedMapOf<String, MutableList<AttachedJarServiceFileEntry>>()
        val fingerprints = mutableListOf<AttachedJarEntryFingerprint>()
        entries.map(AttachedJarEntry::normalized)
            .filter { entry -> entry.enabled && entry.path.isNotBlank() }
            .forEach { entry ->
                val classJarPath = runCatching { Path.of(entry.path).normalize() }.getOrNull()
                    ?.takeIf { path -> Files.isRegularFile(path) }
                    ?: return@forEach
                val sourceJarPath = entry.sourceJarPath
                    ?.let { sourcePath -> runCatching { Path.of(sourcePath).normalize() }.getOrNull() }
                    ?.takeIf { path -> Files.isRegularFile(path) }
                fingerprints += fingerprint(classJarPath, sourceJarPath)
                val attachmentClasses = linkedMapOf<String, MutableAttachedJarClassEntry>()
                indexClassJar(classJarPath, sourceJarPath, attachmentClasses, serviceFiles)
                sourceJarPath?.let { sourcePath ->
                    indexSourceJar(sourcePath, classJarPath, attachmentClasses, serviceFiles)
                }
                attachmentClasses.values.forEach { mutableEntry ->
                    val immutableEntry = mutableEntry.toImmutable()
                    classCandidates.getOrPut(immutableEntry.qualifiedName) { mutableListOf() } += immutableEntry
                }
            }
        return AttachedJarIndex(
            classCandidatesByQualifiedName = classCandidates.mapValues { (_, value) -> value.toList() },
            serviceFilesByInterfaceName = serviceFiles.mapValues { (_, value) -> value.toList() },
            fingerprints = fingerprints,
        )
    }

    /**
     * 遍历 class jar 中的 .class 文件和 META-INF/services 资源，解析字节码头以填充类条目与 SPI 服务文件。
     * 解析过程对单文件异常做容错，确保个别损坏条目不会拖累整个 jar 的索引。
     */
    private fun indexClassJar(
        classJarPath: Path,
        sourceJarPath: Path?,
        classes: MutableMap<String, MutableAttachedJarClassEntry>,
        serviceFiles: MutableMap<String, MutableList<AttachedJarServiceFileEntry>>,
    ) {
        runCatching {
            JarFile(classJarPath.toFile()).use { jar ->
                jar.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) {
                        return@forEach
                    }
                    val name = entry.name
                    when {
                        name.endsWith(".class") && !name.endsWith("module-info.class") -> {
                            val qualifiedName = name.removeSuffix(".class")
                                .replace('/', '.')
                            if (qualifiedName.isNotBlank()) {
                                val bytes = jar.readEntryBytesBounded(
                                    entry,
                                    SourceArchiveReadLimits.MAX_CLASS_ENTRY_BYTES,
                                ) ?: return@forEach
                                val header = runCatching {
                                    ClassFileHeaderParser(bytes).parse()
                                }.getOrNull()
                                val kind = classKind(header)
                                classes.getOrPut(qualifiedName) {
                                    MutableAttachedJarClassEntry(
                                        qualifiedName = qualifiedName,
                                        classJarPath = classJarPath.toString(),
                                        sourceJarPath = sourceJarPath?.toString(),
                                    )
                                }.apply {
                                    classEntryName = name
                                    this.kind = kind
                                    superClassName = header?.superClassName?.replace('/', '.')
                                    interfaceNames = header?.interfaceNames.orEmpty().map { interfaceName ->
                                        interfaceName.replace('/', '.')
                                    }
                                    fields = header?.fields.orEmpty()
                                    methods = header?.methods.orEmpty()
                                }
                            }
                        }
                        name.startsWith("META-INF/services/") -> {
                            val serviceName = name.substringAfter("META-INF/services/").takeIf(String::isNotBlank)
                                ?: return@forEach
                            val serviceText = jar.readEntryTextBounded(
                                entry,
                                SourceArchiveReadLimits.MAX_SERVICE_ENTRY_BYTES,
                            ) ?: return@forEach
                            val providers = providerClassNames(serviceText)
                            serviceFiles.getOrPut(serviceName) { mutableListOf() } += AttachedJarServiceFileEntry(
                                serviceInterfaceName = serviceName,
                                providerClassNames = providers,
                                resourceEntryName = name,
                                jarPath = classJarPath.toString(),
                                sourceJarPath = sourceJarPath?.toString(),
                                resourceJarPath = classJarPath.toString(),
                                origin = SourceOrigin.USER_ATTACHED_CLASS_JAR,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 遍历 source jar 中的源码文件（.java/.kt）和 META-INF/services 资源，
     * 把源码条目反向关联回已存在的类条目；若类条目尚不存在则补建一条仅含源码信息的记录。
     */
    private fun indexSourceJar(
        sourceJarPath: Path,
        classJarPath: Path,
        classes: MutableMap<String, MutableAttachedJarClassEntry>,
        serviceFiles: MutableMap<String, MutableList<AttachedJarServiceFileEntry>>,
    ) {
        runCatching {
            JarFile(sourceJarPath.toFile()).use { jar ->
                jar.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) {
                        return@forEach
                    }
                    val name = entry.name
                    when {
                        name.endsWith(".java") || name.endsWith(".kt") -> {
                            val qualifiedName = name
                                .removeSuffix(".java")
                                .removeSuffix(".kt")
                                .replace('/', '.')
                            if (qualifiedName.isNotBlank()) {
                                classes.getOrPut(qualifiedName) {
                                    MutableAttachedJarClassEntry(
                                        qualifiedName = qualifiedName,
                                        classJarPath = classJarPath.toString(),
                                        sourceJarPath = sourceJarPath.toString(),
                                    )
                                }.apply {
                                    sourceEntryName = name
                                    this.sourceJarPath = sourceJarPath.toString()
                                }
                            }
                        }
                        name.startsWith("META-INF/services/") -> {
                            val serviceName = name.substringAfter("META-INF/services/").takeIf(String::isNotBlank)
                                ?: return@forEach
                            val serviceText = jar.readEntryTextBounded(
                                entry,
                                SourceArchiveReadLimits.MAX_SERVICE_ENTRY_BYTES,
                            ) ?: return@forEach
                            val providers = providerClassNames(serviceText)
                            serviceFiles.getOrPut(serviceName) { mutableListOf() } += AttachedJarServiceFileEntry(
                                serviceInterfaceName = serviceName,
                                providerClassNames = providers,
                                resourceEntryName = name,
                                jarPath = classJarPath.toString(),
                                sourceJarPath = sourceJarPath.toString(),
                                resourceJarPath = sourceJarPath.toString(),
                                origin = SourceOrigin.USER_ATTACHED_SOURCE_JAR,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 计算一对 class/source jar 的指纹（最后修改时间、大小、SHA-256），
     * 用于持久化缓存命中判断，避免重复解析未变更的 jar。
     */
    private fun fingerprint(
        classJarPath: Path,
        sourceJarPath: Path?,
    ): AttachedJarEntryFingerprint =
        AttachedJarEntryFingerprint(
            path = classJarPath.toString(),
            sourceJarPath = sourceJarPath?.toString(),
            classJarLastModifiedMillis = Files.getLastModifiedTime(classJarPath).toMillis(),
            classJarSize = Files.size(classJarPath),
            classJarSha256 = sha256(classJarPath),
            sourceJarLastModifiedMillis = sourceJarPath?.let { path -> Files.getLastModifiedTime(path).toMillis() },
            sourceJarSize = sourceJarPath?.let(Files::size),
            sourceJarSha256 = sourceJarPath?.let(::sha256),
        )

    /** 以流式方式计算文件 SHA-256 摘要并以小写十六进制字符串返回，避免一次性载入大 jar。 */
    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 解析 META-INF/services 文件内容：去掉每行注释（# 之后部分），保留非空且去重的实现类全限定名。 */
    private fun providerClassNames(text: String): List<String> =
        text.lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter(String::isNotBlank)
            .distinct()
            .toList()

    /** 通过字节码访问标记与常量池内容判断类的具体种类（注解 / 枚举 / 接口 / record / 普通类）。 */
    private fun classKind(header: ClassFileHeader?): AttachedJarClassKind =
        when {
            header == null -> AttachedJarClassKind.CLASS
            header.accessFlags and ACC_ANNOTATION != 0 -> AttachedJarClassKind.ANNOTATION
            header.accessFlags and ACC_ENUM != 0 -> AttachedJarClassKind.ENUM
            header.accessFlags and ACC_INTERFACE != 0 -> AttachedJarClassKind.INTERFACE
            header.utf8Constants.contains("Record") && header.utf8Constants.contains("java/lang/Record") ->
                AttachedJarClassKind.RECORD
            else -> AttachedJarClassKind.CLASS
        }

    /** 字节码头解析结果：包含访问标记、本类与父类名、实现接口、字段与方法等关键信息。 */
    private data class ClassFileHeader(
        val accessFlags: Int,
        val utf8Constants: Set<String>,
        val thisClassName: String?,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val fields: List<AttachedJarFieldEntry>,
        val methods: List<AttachedJarMethodEntry>,
    )

    /**
     * 极简的 .class 文件头解析器：手工遍历常量池、访问标记、字段表与方法表，
     * 仅提取关系构建所需的最少信息，避免引入完整 ASM 依赖。遇到任何格式异常返回 null。
     */
    private class ClassFileHeaderParser(private val bytes: ByteArray) {
        // 当前解析游标，逐字节向前推进
        private var offset = 0
        // 已观察到的全部 UTF-8 常量，用于 record 等需要靠关键字判断的场景
        private val utf8Constants = linkedSetOf<String>()
        // 常量池索引到 UTF-8 字符串的映射，供字段/方法名解析复用
        private val utf8ConstantsByIndex = linkedMapOf<Int, String>()
        // 常量池索引到类名 UTF-8 索引的映射，用于解析本类/父类/接口名
        private val classNameIndices = linkedMapOf<Int, Int>()

        /** 解析整段字节码并返回头部信息；数据不完整或魔数不匹配时返回 null。 */
        fun parse(): ClassFileHeader? {
            if (bytes.size < 10 || readU4() != CLASS_FILE_MAGIC) {
                return null
            }
            readU2()
            readU2()
            val constantPoolCount = readU2()
            var index = 1
            while (index < constantPoolCount && offset < bytes.size) {
                when (readU1()) {
                    1 -> {
                        val length = readU2()
                        if (offset + length > bytes.size) {
                            return null
                        }
                        val value = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
                        utf8Constants += value
                        utf8ConstantsByIndex[index] = value
                        offset += length
                    }
                    3, 4 -> skip(4)
                    5, 6 -> {
                        skip(8)
                        index++
                    }
                    7 -> classNameIndices[index] = readU2()
                    8, 16, 19, 20 -> skip(2)
                    9, 10, 11, 12, 17, 18 -> skip(4)
                    15 -> skip(3)
                    else -> return null
                }
                index++
            }
            if (offset + 2 > bytes.size) {
                return null
            }
            val accessFlags = readU2()
            val thisClassIndex = readU2()
            val superClassIndex = readU2()
            val interfaceCount = readU2()
            val interfaceNames = (0 until interfaceCount).mapNotNull {
                className(readU2())
            }
            val fields = readMembers().map { member ->
                AttachedJarFieldEntry(
                    name = member.name,
                    descriptor = member.descriptor,
                    accessFlags = member.accessFlags,
                )
            }
            val methods = readMembers().map { member ->
                AttachedJarMethodEntry(
                    name = member.name,
                    descriptor = member.descriptor,
                    accessFlags = member.accessFlags,
                )
            }
            return ClassFileHeader(
                accessFlags = accessFlags,
                utf8Constants = utf8Constants,
                thisClassName = className(thisClassIndex),
                superClassName = className(superClassIndex),
                interfaceNames = interfaceNames,
                fields = fields,
                methods = methods,
            )
        }

        /** 读取字段表或方法表，提取名称、描述符与访问标记；遇到数据截断时返回已成功解析的部分。 */
        private fun readMembers(): List<ClassFileMember> {
            if (offset + 2 > bytes.size) {
                return emptyList()
            }
            val memberCount = readU2()
            val members = mutableListOf<ClassFileMember>()
            repeat(memberCount) {
                if (offset + 8 > bytes.size) {
                    return members
                }
                val accessFlags = readU2()
                val name = utf8ConstantsByIndex[readU2()]
                val descriptor = utf8ConstantsByIndex[readU2()]
                val attributesCount = readU2()
                repeat(attributesCount) {
                    if (offset + 6 > bytes.size) {
                        return members
                    }
                    readU2()
                    val length = readU4()
                    skip(length.coerceAtLeast(0))
                }
                if (!name.isNullOrBlank() && !descriptor.isNullOrBlank()) {
                    members += ClassFileMember(
                        name = name,
                        descriptor = descriptor,
                        accessFlags = accessFlags,
                    )
                }
            }
            return members
        }

        /** 通过常量池中的类索引解析出对应的内部类名（斜杠分隔），索引为 0 表示无父类（java.lang.Object）。 */
        private fun className(classIndex: Int): String? {
            if (classIndex == 0) {
                return null
            }
            val utf8Index = classNameIndices[classIndex] ?: return null
            return utf8ConstantsByIndex[utf8Index]
        }

        /** 读取 1 字节无符号整数，越界时返回 0 以保证解析不抛异常。 */
        private fun readU1(): Int {
            if (offset >= bytes.size) {
                return 0
            }
            return bytes[offset++].toInt() and 0xff
        }

        /** 按大端序读取 2 字节无符号整数。 */
        private fun readU2(): Int {
            val value = (readU1() shl 8) or readU1()
            return value and 0xffff
        }

        /** 按大端序读取 4 字节有符号整数（魔数等场景使用）。 */
        private fun readU4(): Int =
            (readU1() shl 24) or (readU1() shl 16) or (readU1() shl 8) or readU1()

        /** 跳过指定字节数，自动夹取到数组末尾避免越界。 */
        private fun skip(count: Int) {
            offset = (offset + count).coerceAtMost(bytes.size)
        }

        /** 单个字段或方法的解析中间结构，待外层转换为对外条目。 */
        private data class ClassFileMember(
            val name: String,
            val descriptor: String,
            val accessFlags: Int,
        )
    }

    private companion object {
        // .class 文件的魔数 0xCAFEBABE，用于校验文件是否为合法字节码
        private const val CLASS_FILE_MAGIC: Int = -889275714
        // 访问标记：接口
        private const val ACC_INTERFACE = 0x0200
        // 访问标记：注解类型
        private const val ACC_ANNOTATION = 0x2000
        // 访问标记：枚举类型
        private const val ACC_ENUM = 0x4000
    }
}

/** 索引构建过程中的可变类条目：在解析 class jar 与 source jar 时分别写入不同字段，最终冻结为不可变条目。 */
private data class MutableAttachedJarClassEntry(
    val qualifiedName: String,
    // class jar 中 .class 条目的相对路径
    var classEntryName: String? = null,
    // source jar 中源文件条目的相对路径
    var sourceEntryName: String? = null,
    // 所属 class jar 的本地路径
    val classJarPath: String,
    // 所属 source jar 的本地路径，可能为空
    var sourceJarPath: String?,
    // 类种类，默认普通类，由字节码解析后回填
    var kind: AttachedJarClassKind = AttachedJarClassKind.CLASS,
    // 父类全限定名（来自字节码）
    var superClassName: String? = null,
    // 实现接口的全限定名列表
    var interfaceNames: List<String> = emptyList(),
    // 字段条目列表（来自字节码）
    var fields: List<AttachedJarFieldEntry> = emptyList(),
    // 方法条目列表（来自字节码）
    var methods: List<AttachedJarMethodEntry> = emptyList(),
) {
    /** 将可变条目冻结为对外暴露的不可变条目，避免内部状态被外部修改。 */
    fun toImmutable(): AttachedJarClassEntry =
        AttachedJarClassEntry(
            qualifiedName = qualifiedName,
            classEntryName = classEntryName,
            sourceEntryName = sourceEntryName,
            classJarPath = classJarPath,
            sourceJarPath = sourceJarPath,
            kind = kind,
            superClassName = superClassName,
            interfaceNames = interfaceNames,
            fields = fields,
            methods = methods,
        )
}

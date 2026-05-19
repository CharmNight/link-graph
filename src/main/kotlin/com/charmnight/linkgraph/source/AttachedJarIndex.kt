package com.charmnight.linkgraph.source

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

enum class AttachedJarClassKind {
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION,
    RECORD,
}

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
        sourceJarPath?.let { "$it!/$sourceEntryName" }
            ?: "$classJarPath!/$classEntryName"
}

data class AttachedJarFieldEntry(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
)

data class AttachedJarMethodEntry(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
)

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

class AttachedJarIndex(
    val classesByQualifiedName: Map<String, AttachedJarClassEntry> = emptyMap(),
    val serviceFilesByInterfaceName: Map<String, List<AttachedJarServiceFileEntry>> = emptyMap(),
    val fingerprints: List<AttachedJarEntryFingerprint> = emptyList(),
) {
    fun findClass(qualifiedName: String): AttachedJarClassEntry? =
        classesByQualifiedName[qualifiedName.trim()]

    fun serviceFiles(interfaceName: String): List<AttachedJarServiceFileEntry> =
        serviceFilesByInterfaceName[interfaceName].orEmpty()

    companion object {
        fun build(entries: List<AttachedJarEntry>): AttachedJarIndex =
            AttachedJarIndexBuilder().build(entries)
    }
}

class AttachedJarIndexBuilder {
    fun build(entries: List<AttachedJarEntry>): AttachedJarIndex {
        val classes = linkedMapOf<String, MutableAttachedJarClassEntry>()
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
                indexClassJar(classJarPath, sourceJarPath, classes, serviceFiles)
                sourceJarPath?.let { sourcePath ->
                    indexSourceJar(sourcePath, classJarPath, classes, serviceFiles)
                }
            }
        return AttachedJarIndex(
            classesByQualifiedName = classes.mapValues { (_, value) -> value.toImmutable() },
            serviceFilesByInterfaceName = serviceFiles.mapValues { (_, value) -> value.toList() },
            fingerprints = fingerprints,
        )
    }

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
                                .substringBefore('$')
                            if (qualifiedName.isNotBlank()) {
                                val kind = runCatching {
                                    classKind(jar.getInputStream(entry).readBytes())
                                }.getOrDefault(AttachedJarClassKind.CLASS)
                                classes.getOrPut(qualifiedName) {
                                    MutableAttachedJarClassEntry(
                                        qualifiedName = qualifiedName,
                                        classJarPath = classJarPath.toString(),
                                        sourceJarPath = sourceJarPath?.toString(),
                                    )
                                }.apply {
                                    classEntryName = name
                                    this.kind = kind
                                    val header = runCatching {
                                        ClassFileHeaderParser(jar.getInputStream(entry).readBytes()).parse()
                                    }.getOrNull()
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
                            val providers = providerClassNames(jar.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
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
                            val providers = providerClassNames(jar.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
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

    private fun providerClassNames(text: String): List<String> =
        text.lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter(String::isNotBlank)
            .distinct()
            .toList()

    private fun classKind(bytes: ByteArray): AttachedJarClassKind {
        val parser = ClassFileHeaderParser(bytes)
        val header = parser.parse() ?: return AttachedJarClassKind.CLASS
        return when {
            header.accessFlags and ACC_ANNOTATION != 0 -> AttachedJarClassKind.ANNOTATION
            header.accessFlags and ACC_ENUM != 0 -> AttachedJarClassKind.ENUM
            header.accessFlags and ACC_INTERFACE != 0 -> AttachedJarClassKind.INTERFACE
            header.utf8Constants.contains("Record") && header.utf8Constants.contains("java/lang/Record") ->
                AttachedJarClassKind.RECORD
            else -> AttachedJarClassKind.CLASS
        }
    }

    private data class ClassFileHeader(
        val accessFlags: Int,
        val utf8Constants: Set<String>,
        val thisClassName: String?,
        val superClassName: String?,
        val interfaceNames: List<String>,
        val fields: List<AttachedJarFieldEntry>,
        val methods: List<AttachedJarMethodEntry>,
    )

    private class ClassFileHeaderParser(private val bytes: ByteArray) {
        private var offset = 0
        private val utf8Constants = linkedSetOf<String>()
        private val utf8ConstantsByIndex = linkedMapOf<Int, String>()
        private val classNameIndices = linkedMapOf<Int, Int>()

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

        private fun className(classIndex: Int): String? {
            if (classIndex == 0) {
                return null
            }
            val utf8Index = classNameIndices[classIndex] ?: return null
            return utf8ConstantsByIndex[utf8Index]
        }

        private fun readU1(): Int {
            if (offset >= bytes.size) {
                return 0
            }
            return bytes[offset++].toInt() and 0xff
        }

        private fun readU2(): Int {
            val value = (readU1() shl 8) or readU1()
            return value and 0xffff
        }

        private fun readU4(): Int =
            (readU1() shl 24) or (readU1() shl 16) or (readU1() shl 8) or readU1()

        private fun skip(count: Int) {
            offset = (offset + count).coerceAtMost(bytes.size)
        }

        private data class ClassFileMember(
            val name: String,
            val descriptor: String,
            val accessFlags: Int,
        )
    }

    private companion object {
        private const val CLASS_FILE_MAGIC: Int = -889275714
        private const val ACC_INTERFACE = 0x0200
        private const val ACC_ANNOTATION = 0x2000
        private const val ACC_ENUM = 0x4000
    }
}

private data class MutableAttachedJarClassEntry(
    val qualifiedName: String,
    var classEntryName: String? = null,
    var sourceEntryName: String? = null,
    val classJarPath: String,
    var sourceJarPath: String?,
    var kind: AttachedJarClassKind = AttachedJarClassKind.CLASS,
    var superClassName: String? = null,
    var interfaceNames: List<String> = emptyList(),
    var fields: List<AttachedJarFieldEntry> = emptyList(),
    var methods: List<AttachedJarMethodEntry> = emptyList(),
) {
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

package com.charmnight.linkgraph.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class AttachedJarIndexTest {
    @Test
    fun preservesExactBinaryNamesForOuterAndInnerClasses() {
        val dir = Files.createTempDirectory("attached-jar-index-inner")
        val classJar = dir.resolve("nested.jar")
        val sourceJar = dir.resolve("nested-sources.jar")
        writeJar(
            classJar,
            mapOf(
                "com/example/Outer.class" to byteArrayOf(0),
                "com/example/Outer\$Inner.class" to byteArrayOf(0),
                "com/example/Outer\$Inner\$Nested.class" to byteArrayOf(0),
            ),
        )
        writeJar(sourceJar, mapOf("com/example/Outer.java" to "class Outer {}".toByteArray()))

        val index = AttachedJarIndex.build(
            listOf(AttachedJarEntry(path = classJar.toString(), sourceJarPath = sourceJar.toString())),
        )

        assertEquals("com/example/Outer.class", index.findClass("com.example.Outer")?.classEntryName)
        val inner = assertNotNull(index.findClass("com.example.Outer\$Inner"))
        assertEquals("com/example/Outer\$Inner.class", inner.classEntryName)
        assertNull(inner.sourceEntryName)
        assertTrue(inner.displayPath.startsWith(classJar.toString()))
        assertEquals(
            "com/example/Outer\$Inner\$Nested.class",
            index.findClass("com.example.Outer\$Inner\$Nested")?.classEntryName,
        )
    }

    @Test
    fun keepsDuplicateClassAttachmentsAsSeparateCandidates() {
        val dir = Files.createTempDirectory("attached-jar-index-duplicates")
        val firstClassJar = dir.resolve("first.jar")
        val firstSourceJar = dir.resolve("first-sources.jar")
        val secondClassJar = dir.resolve("second.jar")
        val secondSourceJar = dir.resolve("second-sources.jar")
        writeJar(firstClassJar, mapOf("com/example/Duplicate.class" to byteArrayOf(1)))
        writeJar(firstSourceJar, mapOf("com/example/Duplicate.java" to "class First {}".toByteArray()))
        writeJar(secondClassJar, mapOf("com/example/Duplicate.class" to byteArrayOf(2)))
        writeJar(secondSourceJar, mapOf("com/example/Duplicate.java" to "class Second {}".toByteArray()))

        val index = AttachedJarIndex.build(
            listOf(
                AttachedJarEntry(path = firstClassJar.toString(), sourceJarPath = firstSourceJar.toString()),
                AttachedJarEntry(path = secondClassJar.toString(), sourceJarPath = secondSourceJar.toString()),
            ),
        )

        val candidates = index.findClassCandidates("com.example.Duplicate")
        assertEquals(2, candidates.size)
        assertEquals(firstClassJar.toString(), candidates[0].classJarPath)
        assertEquals(firstSourceJar.toString(), candidates[0].sourceJarPath)
        assertEquals(secondClassJar.toString(), candidates[1].classJarPath)
        assertEquals(secondSourceJar.toString(), candidates[1].sourceJarPath)
    }

    @Test
    fun indexesClassesSourcesAndServiceFiles() {
        val dir = Files.createTempDirectory("attached-jar-index")
        val classJar = dir.resolve("external.jar")
        val sourceJar = dir.resolve("external-sources.jar")
        writeJar(
            classJar,
            mapOf(
                "com/example/spi/PaymentProvider.class" to byteArrayOf(0),
                "META-INF/services/com.example.spi.PaymentService" to "com.example.spi.PaymentProvider\n".toByteArray(),
            ),
        )
        writeJar(
            sourceJar,
            mapOf(
                "com/example/spi/PaymentProvider.java" to "package com.example.spi; class PaymentProvider {}\n".toByteArray(),
            ),
        )

        val index = AttachedJarIndex.build(
            listOf(AttachedJarEntry(path = classJar.toString(), sourceJarPath = sourceJar.toString())),
        )

        val classEntry = assertNotNull(index.findClass("com.example.spi.PaymentProvider"))
        assertEquals("com/example/spi/PaymentProvider.class", classEntry.classEntryName)
        assertEquals("com/example/spi/PaymentProvider.java", classEntry.sourceEntryName)
        val serviceFiles = index.serviceFiles("com.example.spi.PaymentService")
        assertEquals(1, serviceFiles.size)
        assertEquals(listOf("com.example.spi.PaymentProvider"), serviceFiles.single().providerClassNames)
        assertEquals(SourceOrigin.USER_ATTACHED_CLASS_JAR, serviceFiles.single().origin)
        assertEquals(classJar.toString(), serviceFiles.single().resourceJarPath)
        assertTrue(index.fingerprints.isNotEmpty())
    }

    @Test
    fun indexesSourceJarServiceFileWithSourceOrigin() {
        val dir = Files.createTempDirectory("attached-jar-index")
        val classJar = dir.resolve("external.jar")
        val sourceJar = dir.resolve("external-sources.jar")
        writeJar(
            classJar,
            mapOf(
                "com/example/spi/PaymentProvider.class" to byteArrayOf(0),
            ),
        )
        writeJar(
            sourceJar,
            mapOf(
                "com/example/spi/PaymentProvider.java" to "package com.example.spi; class PaymentProvider {}\n".toByteArray(),
                "META-INF/services/com.example.spi.PaymentService" to "com.example.spi.PaymentProvider\n".toByteArray(),
            ),
        )

        val index = AttachedJarIndex.build(
            listOf(AttachedJarEntry(path = classJar.toString(), sourceJarPath = sourceJar.toString())),
        )

        val serviceFile = index.serviceFiles("com.example.spi.PaymentService").single()
        assertEquals(SourceOrigin.USER_ATTACHED_SOURCE_JAR, serviceFile.origin)
        assertEquals(sourceJar.toString(), serviceFile.resourceJarPath)
    }

    @Test
    fun indexesClassKindsFromBytecode() {
        val dir = Files.createTempDirectory("attached-jar-index")
        val sources = dir.resolve("src/com/example/external")
        Files.createDirectories(sources)
        Files.writeString(
            sources.resolve("PlainClass.java"),
            "package com.example.external; public class PlainClass {}\n",
        )
        Files.writeString(
            sources.resolve("ExternalInterface.java"),
            "package com.example.external; public interface ExternalInterface {}\n",
        )
        Files.writeString(
            sources.resolve("ExternalEnum.java"),
            "package com.example.external; public enum ExternalEnum { ONE }\n",
        )
        Files.writeString(
            sources.resolve("ExternalAnnotation.java"),
            "package com.example.external; public @interface ExternalAnnotation {}\n",
        )
        Files.writeString(
            sources.resolve("ExternalRecord.java"),
            "package com.example.external; public record ExternalRecord(String value) {}\n",
        )
        val classesDir = dir.resolve("classes")
        Files.createDirectories(classesDir)
        val javac = ProcessBuilder(
            "javac",
            "-d",
            classesDir.toString(),
            sources.resolve("PlainClass.java").toString(),
            sources.resolve("ExternalInterface.java").toString(),
            sources.resolve("ExternalEnum.java").toString(),
            sources.resolve("ExternalAnnotation.java").toString(),
            sources.resolve("ExternalRecord.java").toString(),
        )
            .redirectErrorStream(true)
            .start()
        val javacOutput = javac.inputStream.bufferedReader().readText()
        assertEquals(0, javac.waitFor(), javacOutput)
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf(
                "com/example/external/PlainClass.class" to Files.readAllBytes(classesDir.resolve("com/example/external/PlainClass.class")),
                "com/example/external/ExternalInterface.class" to Files.readAllBytes(classesDir.resolve("com/example/external/ExternalInterface.class")),
                "com/example/external/ExternalEnum.class" to Files.readAllBytes(classesDir.resolve("com/example/external/ExternalEnum.class")),
                "com/example/external/ExternalAnnotation.class" to Files.readAllBytes(classesDir.resolve("com/example/external/ExternalAnnotation.class")),
                "com/example/external/ExternalRecord.class" to Files.readAllBytes(classesDir.resolve("com/example/external/ExternalRecord.class")),
            ),
        )

        val index = AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))

        assertEquals(AttachedJarClassKind.CLASS, index.findClass("com.example.external.PlainClass")?.kind)
        assertEquals(AttachedJarClassKind.INTERFACE, index.findClass("com.example.external.ExternalInterface")?.kind)
        assertEquals(AttachedJarClassKind.ENUM, index.findClass("com.example.external.ExternalEnum")?.kind)
        assertEquals(AttachedJarClassKind.ANNOTATION, index.findClass("com.example.external.ExternalAnnotation")?.kind)
        assertEquals(AttachedJarClassKind.RECORD, index.findClass("com.example.external.ExternalRecord")?.kind)
    }

    @Test
    fun indexesClassHeaderInheritanceFromBytecode() {
        val dir = Files.createTempDirectory("attached-jar-index")
        val sources = dir.resolve("src/com/example/external")
        Files.createDirectories(sources)
        Files.writeString(
            sources.resolve("ExternalService.java"),
            "package com.example.external; public interface ExternalService {}\n",
        )
        Files.writeString(
            sources.resolve("BaseProvider.java"),
            "package com.example.external; public class BaseProvider {}\n",
        )
        Files.writeString(
            sources.resolve("ExternalProvider.java"),
            "package com.example.external; public class ExternalProvider extends BaseProvider implements ExternalService {}\n",
        )
        val classesDir = dir.resolve("classes")
        Files.createDirectories(classesDir)
        val javac = ProcessBuilder(
            "javac",
            "-d",
            classesDir.toString(),
            sources.resolve("ExternalService.java").toString(),
            sources.resolve("BaseProvider.java").toString(),
            sources.resolve("ExternalProvider.java").toString(),
        )
            .redirectErrorStream(true)
            .start()
        val javacOutput = javac.inputStream.bufferedReader().readText()
        assertEquals(0, javac.waitFor(), javacOutput)
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf(
                "com/example/external/ExternalService.class" to Files.readAllBytes(classesDir.resolve("com/example/external/ExternalService.class")),
                "com/example/external/BaseProvider.class" to Files.readAllBytes(classesDir.resolve("com/example/external/BaseProvider.class")),
                "com/example/external/ExternalProvider.class" to Files.readAllBytes(classesDir.resolve("com/example/external/ExternalProvider.class")),
            ),
        )

        val index = AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))
        val provider = assertNotNull(index.findClass("com.example.external.ExternalProvider"))

        assertEquals("com.example.external.BaseProvider", provider.superClassName)
        assertEquals(listOf("com.example.external.ExternalService"), provider.interfaceNames)
    }

    @Test
    fun indexesClassHeaderMembersFromBytecode() {
        val dir = Files.createTempDirectory("attached-jar-index")
        val sources = dir.resolve("src/com/example/external")
        Files.createDirectories(sources)
        Files.writeString(
            sources.resolve("ExternalClient.java"),
            """
            package com.example.external;
            public class ExternalClient {
                public String endpoint;
                public int retryCount;
                public String call(String id, int attempts) { return id + attempts; }
                public static ExternalClient create() { return new ExternalClient(); }
            }
            """.trimIndent(),
        )
        val classesDir = dir.resolve("classes")
        Files.createDirectories(classesDir)
        val javac = ProcessBuilder(
            "javac",
            "-d",
            classesDir.toString(),
            sources.resolve("ExternalClient.java").toString(),
        )
            .redirectErrorStream(true)
            .start()
        val javacOutput = javac.inputStream.bufferedReader().readText()
        assertEquals(0, javac.waitFor(), javacOutput)
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf(
                "com/example/external/ExternalClient.class" to Files.readAllBytes(classesDir.resolve("com/example/external/ExternalClient.class")),
            ),
        )

        val index = AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))
        val client = assertNotNull(index.findClass("com.example.external.ExternalClient"))

        assertTrue(client.fields.any { field -> field.name == "endpoint" && field.descriptor == "Ljava/lang/String;" })
        assertTrue(client.fields.any { field -> field.name == "retryCount" && field.descriptor == "I" })
        assertTrue(client.methods.any { method -> method.name == "call" && method.descriptor == "(Ljava/lang/String;I)Ljava/lang/String;" })
        assertTrue(client.methods.any { method -> method.name == "create" && method.descriptor == "()Lcom/example/external/ExternalClient;" })
    }

    @Test
    fun fingerprintsIncludeContentHash() {
        val dir = Files.createTempDirectory("attached-jar-index")
        val firstJar = dir.resolve("external-a.jar")
        val secondJar = dir.resolve("external-b.jar")
        writeJar(firstJar, mapOf("com/example/A.class" to byteArrayOf(1)))
        writeJar(secondJar, mapOf("com/example/A.class" to byteArrayOf(2)))

        val first = AttachedJarIndex.build(listOf(AttachedJarEntry(path = firstJar.toString())))
        val second = AttachedJarIndex.build(listOf(AttachedJarEntry(path = secondJar.toString())))

        assertNotEquals(
            first.fingerprints.single().classJarSha256,
            second.fingerprints.single().classJarSha256,
        )
    }

    @Test
    fun skipsOversizedClassEntries() {
        val dir = Files.createTempDirectory("attached-jar-index")
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf("com/example/Huge.class" to ByteArray(2 * 1024 * 1024 + 1)),
        )

        val index = AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))

        assertNull(index.findClass("com.example.Huge"))
    }

    @Test
    fun skipsOversizedServiceFiles() {
        val dir = Files.createTempDirectory("attached-jar-index")
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf(
                "com/example/Provider.class" to byteArrayOf(0),
                "META-INF/services/com.example.Service" to ByteArray(64 * 1024 + 1) { 'A'.code.toByte() },
            ),
        )

        val index = AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))

        assertTrue(index.serviceFiles("com.example.Service").isEmpty())
    }

    private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }
}

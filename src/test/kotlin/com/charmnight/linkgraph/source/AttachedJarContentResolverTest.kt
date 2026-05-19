package com.charmnight.linkgraph.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class AttachedJarContentResolverTest {
    @Test
    fun readsSourceJarBeforeClassJar() {
        val dir = Files.createTempDirectory("attached-jar-resolver")
        val classJar = dir.resolve("external.jar")
        val sourceJar = dir.resolve("external-sources.jar")
        writeJar(classJar, mapOf("com/example/ExternalService.class" to byteArrayOf(0)))
        writeJar(
            sourceJar,
            mapOf("com/example/ExternalService.java" to "package com.example; class ExternalService {}\n".toByteArray()),
        )
        val resolver = AttachedJarContentResolver(
            listOf(AttachedJarEntry(path = classJar.toString(), sourceJarPath = sourceJar.toString())),
        )

        val content = assertNotNull(resolver.readClassByQualifiedName("com.example.ExternalService"))

        assertEquals(SourceOrigin.USER_ATTACHED_SOURCE_JAR, content.origin)
        assertEquals(false, content.decompiled)
        assertTrue(content.text.contains("class ExternalService"))
        assertTrue(content.virtualFileUrl?.startsWith("jar://") == true)
    }

    @Test
    fun rejectsClassJarWhenSourcesAndRealDecompilerOutputAreMissing() {
        val dir = Files.createTempDirectory("attached-jar-resolver")
        val classJar = dir.resolve("external.jar")
        writeJar(classJar, mapOf("com/example/ExternalService.class" to byteArrayOf(0)))
        val resolver = AttachedJarContentResolver(listOf(AttachedJarEntry(path = classJar.toString())))

        assertEquals(null, resolver.readClassByQualifiedName("com.example.ExternalService"))
        assertNotNull(resolver.lastUnavailableReason)
    }

    @Test
    fun reportsWhenClassJarDecompileIsDisabled() {
        val dir = Files.createTempDirectory("attached-jar-resolver")
        val classJar = dir.resolve("external.jar")
        writeJar(classJar, mapOf("com/example/ExternalService.class" to byteArrayOf(0)))
        val resolver = AttachedJarContentResolver(
            listOf(AttachedJarEntry(path = classJar.toString())),
            allowDecompile = false,
        )

        assertEquals(null, resolver.readClassByQualifiedName("com.example.ExternalService"))
        assertEquals("CLASS_JAR_DECOMPILE_DISABLED", resolver.lastUnavailableReason)
    }

    @Test
    fun readsRealDecompiledClassJarWhenSourcesMissing() {
        val dir = Files.createTempDirectory("attached-jar-real-decompile")
        val sourceFile = dir.resolve("src/com/example/CompiledExternalService.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(
            sourceFile,
            """
            package com.example;
            public class CompiledExternalService {
                public String name() {
                    return "compiled";
                }
            }
            """.trimIndent(),
        )
        val classesDir = dir.resolve("classes")
        Files.createDirectories(classesDir)
        assertEquals(
            0,
            ProcessBuilder("javac", "-d", classesDir.toString(), sourceFile.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor(),
        )
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf(
                "com/example/CompiledExternalService.class" to
                    Files.readAllBytes(classesDir.resolve("com/example/CompiledExternalService.class")),
            ),
        )
        assertNotNull(
            AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))
                .findClass("com.example.CompiledExternalService"),
            "compiled class should be indexed from attached class jar",
        )
        assertNotNull(
            ClassFileSourceDecompiler.decompileJarEntry(
                classJar,
                "com/example/CompiledExternalService.class",
            ),
            "compiled class should produce real decompiled Java text",
        )
        val resolver = AttachedJarContentResolver(listOf(AttachedJarEntry(path = classJar.toString())))

        val content = assertNotNull(resolver.readClassByQualifiedName("com.example.CompiledExternalService"))

        assertEquals(SourceOrigin.USER_ATTACHED_CLASS_JAR, content.origin)
        assertEquals(true, content.decompiled)
        assertTrue(content.text.contains("CompiledExternalService"), content.text)
        assertTrue(content.text.contains("compiled"), content.text)
        assertTrue(!content.text.contains("Decompiled class stub"), content.text)
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

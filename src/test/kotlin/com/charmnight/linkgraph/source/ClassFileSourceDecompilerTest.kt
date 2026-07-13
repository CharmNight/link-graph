package com.charmnight.linkgraph.source

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassFileSourceDecompilerTest {
    @Test
    fun fernflowerInputContainsOnlyRequestedClassFamily() {
        val dir = Files.createTempDirectory("fernflower-input")
        val sourceJar = dir.resolve("library.jar")
        val boundedJar = dir.resolve("bounded.jar")
        writeJar(
            sourceJar,
            mapOf(
                "com/example/Outer.class" to byteArrayOf(1),
                "com/example/Outer\$Inner.class" to byteArrayOf(2),
                "com/example/Outer\$Inner\$Nested.class" to byteArrayOf(3),
                "com/example/Unrelated.class" to byteArrayOf(4),
                "META-INF/services/com.example.Service" to byteArrayOf(5),
            ),
        )

        val copiedEntries = ClassFileSourceDecompiler.createBoundedFernflowerInputJar(
            sourceJar = sourceJar,
            requestedClassEntryName = "com/example/Outer.class",
            targetJar = boundedJar,
        )

        assertEquals(
            listOf(
                "com/example/Outer.class",
                "com/example/Outer\$Inner.class",
                "com/example/Outer\$Inner\$Nested.class",
            ),
            copiedEntries,
        )
        JarFile(boundedJar.toFile()).use { jar ->
            assertEquals(copiedEntries, jar.entries().asSequence().map(JarEntry::getName).toList())
        }
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

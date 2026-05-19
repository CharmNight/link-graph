package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.jvm.relation.JvmResolutionContext
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationResolverRegistry
import com.charmnight.linkgraph.source.AttachedJarEntry
import com.charmnight.linkgraph.source.AttachedJarIndex
import com.charmnight.linkgraph.source.AttachedJarContentResolver
import com.charmnight.linkgraph.source.SourceOrigin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmSymbolIndexBuilderAttachedJarTest : BasePlatformTestCase() {
    fun testProjectClassJavadocIsIndexedForUmlDisplay() {
        myFixture.addFileToProject(
            "src/main/java/com/example/docs/DocumentedService.java",
            """
            package com.example.docs;

            /**
             * Coordinates documented task execution.
             */
            public abstract class DocumentedService {
                public abstract void run();
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val service = requireNotNull(symbolIndex.findClass("com.example.docs.DocumentedService"))

        assertEquals("Coordinates documented task execution.", service.docComment)
        assertEquals(true, service.abstract)
    }

    fun testAttachedSpiProviderIsIndexedWithoutExternalLibraryExpansion() {
        val dir = Files.createTempDirectory("attached-jar-symbol-index")
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf(
                "com/external/ExternalService.class" to byteArrayOf(0),
                "com/external/ExternalProvider.class" to byteArrayOf(0),
                "META-INF/services/com.external.ExternalService" to "com.external.ExternalProvider\n".toByteArray(),
            ),
        )
        val attachedIndex = AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))

        val symbolIndex = JvmSymbolIndexBuilder(project) { attachedIndex }.build(
            JvmResolutionBudget(
                includeExternalLibraries = false,
                includeUserAttachedJars = true,
            ),
        )

        assertNotNull(symbolIndex.findClass("com.external.ExternalService"))
        assertNotNull(symbolIndex.findClass("com.external.ExternalProvider"))
    }

    fun testAttachedSpiProviderImplementingServiceIsProvenWithoutPsiClasspath() {
        val dir = Files.createTempDirectory("attached-jar-symbol-index")
        val sourceDir = dir.resolve("src/com/external")
        Files.createDirectories(sourceDir)
        Files.writeString(
            sourceDir.resolve("ExternalService.java"),
            "package com.external; public interface ExternalService { void run(); }\n",
        )
        Files.writeString(
            sourceDir.resolve("ExternalProvider.java"),
            "package com.external; public class ExternalProvider implements ExternalService { public void run() {} }\n",
        )
        val classesDir = dir.resolve("classes")
        Files.createDirectories(classesDir)
        val javac = ProcessBuilder(
            "javac",
            "-d",
            classesDir.toString(),
            sourceDir.resolve("ExternalService.java").toString(),
            sourceDir.resolve("ExternalProvider.java").toString(),
        )
            .redirectErrorStream(true)
            .start()
        val javacOutput = javac.inputStream.bufferedReader().readText()
        assertEquals(0, javac.waitFor(), javacOutput)
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf(
                "com/external/ExternalService.class" to Files.readAllBytes(classesDir.resolve("com/external/ExternalService.class")),
                "com/external/ExternalProvider.class" to Files.readAllBytes(classesDir.resolve("com/external/ExternalProvider.class")),
                "META-INF/services/com.external.ExternalService" to "com.external.ExternalProvider\n".toByteArray(),
            ),
        )
        val attachedIndex = AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))
        val budget = JvmResolutionBudget(
            includeExternalLibraries = false,
            includeUserAttachedJars = true,
        )
        val symbolIndex = JvmSymbolIndexBuilder(project) { attachedIndex }.build(budget)
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = AttachedJarContentResolver(attachedIndex),
                budget = budget,
            ),
        )
        val service = requireNotNull(symbolIndex.findClass("com.external.ExternalService"))
        val provider = requireNotNull(symbolIndex.findClass("com.external.ExternalProvider"))
        val relationSummary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.confidence} ${relation.metadata}"
        }

        assertNotNull(relationIndex.relations.singleOrNull { relation ->
            relation.kind == JvmRelationKind.IMPLEMENTS &&
                relation.fromSymbolId == provider.id &&
                relation.toSymbolId == service.id &&
                relation.confidence == JvmRelationConfidence.PROVEN
        }, relationSummary)
        assertNotNull(relationIndex.relations.singleOrNull { relation ->
            relation.kind == JvmRelationKind.SPI_PROVIDES &&
                relation.fromSymbolId == provider.id &&
                relation.toSymbolId == service.id &&
                relation.confidence == JvmRelationConfidence.PROVEN
        }, relationSummary)
    }

    fun testAttachedJarMethodsAndFieldsAreIndexedAtMemberLevel() {
        val dir = Files.createTempDirectory("attached-jar-symbol-index")
        val sourceDir = dir.resolve("src/com/external")
        Files.createDirectories(sourceDir)
        Files.writeString(
            sourceDir.resolve("ExternalClient.java"),
            """
            package com.external;
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
            sourceDir.resolve("ExternalClient.java").toString(),
        )
            .redirectErrorStream(true)
            .start()
        val javacOutput = javac.inputStream.bufferedReader().readText()
        assertEquals(0, javac.waitFor(), javacOutput)
        val classJar = dir.resolve("external.jar")
        writeJar(
            classJar,
            mapOf(
                "com/external/ExternalClient.class" to Files.readAllBytes(classesDir.resolve("com/external/ExternalClient.class")),
            ),
        )
        val attachedIndex = AttachedJarIndex.build(listOf(AttachedJarEntry(path = classJar.toString())))

        val symbolIndex = JvmSymbolIndexBuilder(project) { attachedIndex }.build(
            JvmResolutionBudget(
                includeExternalLibraries = false,
                includeUserAttachedJars = true,
            ),
        )

        assertNotNull(symbolIndex.findField("com.external.ExternalClient.endpoint"))
        assertNotNull(symbolIndex.findField("com.external.ExternalClient.retryCount"))
        assertNotNull(symbolIndex.findMethod("com.external.ExternalClient.call(java.lang.String,int):java.lang.String"))
        assertNotNull(symbolIndex.findMethod("com.external.ExternalClient.create():com.external.ExternalClient"))
    }

    fun testAttachedSourceJarSpiResourceKeepsSourceOrigin() {
        val dir = Files.createTempDirectory("attached-jar-symbol-index")
        val classJar = dir.resolve("external.jar")
        val sourceJar = dir.resolve("external-sources.jar")
        writeJar(classJar, mapOf("com/external/ExternalProvider.class" to byteArrayOf(0)))
        writeJar(
            sourceJar,
            mapOf(
                "com/external/ExternalProvider.java" to "package com.external; class ExternalProvider {}\n".toByteArray(),
                "META-INF/services/com.external.ExternalService" to "com.external.ExternalProvider\n".toByteArray(),
            ),
        )
        val attachedIndex = AttachedJarIndex.build(
            listOf(AttachedJarEntry(path = classJar.toString(), sourceJarPath = sourceJar.toString())),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project) { attachedIndex }.build(
            JvmResolutionBudget(
                includeExternalLibraries = false,
                includeUserAttachedJars = true,
            ),
        )

        val resource = symbolIndex.resourcesByPath.values.single { symbol ->
            symbol.path.endsWith("META-INF/services/com.external.ExternalService")
        }
        assertEquals(SourceOrigin.USER_ATTACHED_SOURCE_JAR, resource.origin)
        assertEquals("jar://${sourceJar}!/META-INF/services/com.external.ExternalService", resource.source?.virtualFileUrl)
    }

    fun testJdkSpiServiceFilesFollowIncludeJdkBudget() {
        val jdkSpiFile = assertNotNull(
            resolveJdkFileSystemProviderServiceFile(),
            "The test fixture JDK must expose a real JRT SPI service file for this budget check.",
        )
        assertEquals(true, jdkSpiFile.url.startsWith("jrt://"))

        val withoutJdk = JvmSymbolIndexBuilder(project).build(
            JvmResolutionBudget(
                includeExternalLibraries = true,
                includeJdk = false,
            ),
        )
        val withJdk = JvmSymbolIndexBuilder(project).build(
            JvmResolutionBudget(
                includeExternalLibraries = true,
                includeJdk = true,
            ),
        )
        val withJdkOnly = JvmSymbolIndexBuilder(project).build(
            JvmResolutionBudget(
                includeExternalLibraries = false,
                includeJdk = true,
            ),
        )

        assertEquals(
            emptyList(),
            withoutJdk.serviceProviderIndex.providersFor("java.nio.file.spi.FileSystemProvider"),
            "includeJdk=false must exclude JRT SPI provider files from the JVM symbol index.",
        )
        val jdkServiceFiles = withJdk.serviceProviderIndex.providersFor("java.nio.file.spi.FileSystemProvider")
        val jdkOnlyServiceFiles = withJdkOnly.serviceProviderIndex.providersFor("java.nio.file.spi.FileSystemProvider")
        assertNotNull(jdkServiceFiles.singleOrNull { file ->
            file.resource.source?.virtualFileUrl?.startsWith("jrt://") == true
        })
        assertNotNull(jdkOnlyServiceFiles.singleOrNull { file ->
            file.resource.source?.virtualFileUrl?.startsWith("jrt://") == true
        })
        assertEquals(
            true,
            jdkServiceFiles.any { file ->
                file.origin in setOf(SourceOrigin.JDK_CLASS, SourceOrigin.JDK_SOURCE) &&
                    file.providerClassNames.contains("jdk.internal.jrtfs.JrtFileSystemProvider")
            },
            "includeJdk=true must index the real JDK FileSystemProvider SPI declaration.",
        )
        assertEquals(
            true,
            jdkOnlyServiceFiles.any { file ->
                file.providerClassNames.contains("jdk.internal.jrtfs.JrtFileSystemProvider")
            },
            "includeJdk=true must not depend on includeExternalLibraries=true for JDK SPI indexing.",
        )
    }

    private fun writeJar(path: java.nio.file.Path, entries: Map<String, ByteArray>) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }

    private fun hasJdkFileSystemProviderServiceFile(): Boolean {
        val roots = ProjectRootManager.getInstance(project).orderEntries().classes().roots.asSequence() +
            ProjectRootManager.getInstance(project).orderEntries().sources().roots.asSequence()
        return roots.any { root ->
            root.url.startsWith("jrt://") &&
                root.findFileByRelativePath("META-INF/services/java.nio.file.spi.FileSystemProvider") != null
        }
    }

    private fun resolveJdkFileSystemProviderServiceFile(): com.intellij.openapi.vfs.VirtualFile? {
        if (hasJdkFileSystemProviderServiceFile()) {
            return ProjectRootManager.getInstance(project).orderEntries().classes().roots.asSequence()
                .plus(ProjectRootManager.getInstance(project).orderEntries().sources().roots.asSequence())
                .firstNotNullOfOrNull { root ->
                    root.takeIf { it.url.startsWith("jrt://") }
                        ?.findFileByRelativePath("META-INF/services/java.nio.file.spi.FileSystemProvider")
                }
        }
        val jdkHome = System.getProperty("java.home").orEmpty()
        val url = "jrt://$jdkHome!/java.base/META-INF/services/java.nio.file.spi.FileSystemProvider"
        return VirtualFileManager.getInstance().findFileByUrl(url)
            ?: VirtualFileManager.getInstance().refreshAndFindFileByUrl(url)
    }
}

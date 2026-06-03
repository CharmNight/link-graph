package com.charmnight.linkgraph.architecture.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectSlicePlannerTest {
    @Test
    fun groupsJvmSourcesByModuleSourceSetPackageAndResourcesByKind() {
        val manifest = ProjectSlicePlanner(projectLocationHash = "project-hash").plan(
            files = listOf(
                ProjectSliceInputFile("orders", "/repo", "src/main/java/com/example/orders/OrderService.java", 10, 1),
                ProjectSliceInputFile("orders", "/repo", "src/main/kotlin/com/example/orders/OrderController.kt", 11, 2),
                ProjectSliceInputFile("orders", "/repo", "src/test/java/com/example/orders/OrderServiceTest.java", 12, 3),
                ProjectSliceInputFile("orders", "/repo", "src/main/resources/application.yml", 13, 4),
                ProjectSliceInputFile("orders", "/repo", "src/main/resources/META-INF/services/com.example.Plugin", 14, 5),
                ProjectSliceInputFile("billing", "/repo", "libs/payment-client.jar", 15, 6, attachedJarFingerprint = "jar-fp"),
            ),
        )

        assertEquals("project-hash", manifest.projectLocationHash)
        assertTrue(manifest.slices.any { slice ->
            slice.moduleName == "orders" &&
                slice.sourceSet == "main" &&
                slice.packagePrefix == "com.example.orders" &&
                slice.kind == ProjectSliceKind.JVM_SOURCE.name &&
                slice.files.map(ProjectFileFingerprint::relativePath).contains("src/main/java/com/example/orders/OrderService.java")
        })
        assertTrue(manifest.slices.any { slice ->
            slice.moduleName == "orders" &&
                slice.sourceSet == "test" &&
                slice.packagePrefix == "com.example.orders" &&
                slice.kind == ProjectSliceKind.JVM_SOURCE.name
        })
        assertTrue(manifest.slices.any { slice ->
            slice.kind == ProjectSliceKind.RESOURCE.name &&
                slice.packagePrefix == "yaml" &&
                slice.files.single().relativePath == "src/main/resources/application.yml"
        })
        assertTrue(manifest.slices.any { slice ->
            slice.kind == ProjectSliceKind.RESOURCE.name &&
                slice.packagePrefix == "spi" &&
                slice.files.single().relativePath == "src/main/resources/META-INF/services/com.example.Plugin"
        })
        assertTrue(manifest.slices.any { slice ->
            slice.kind == ProjectSliceKind.ATTACHED_JAR.name &&
                slice.id.contains("jar-fp") &&
                slice.files.single().contentSha256 == "jar-fp"
        })
    }
}

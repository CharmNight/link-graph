package com.charmnight.linkgraph.architecture.memory

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistentArchitectureIndexCacheStoreTest {
    @Test
    fun writesAndReadsFragmentsOnlyUnderIdeCacheDirectory() {
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val projectRoot = Files.createTempDirectory("link-graph-project")
        val store = PersistentArchitectureIndexCacheStore(cacheRoot)
        val key = ArchitectureIndexFragmentCacheKey(
            projectLocationHash = "project",
            budgetHash = "budget",
            sliceId = "slice:orders",
            fileHash = "file",
            attachedJarFingerprint = null,
        )
        val fragment = ArchitectureIndexSliceFragment(
            sliceId = "slice:orders",
            symbols = listOf(
                SymbolSliceFragment(
                    id = "class:OrderService",
                    qualifiedName = "com.example.orders.OrderService",
                    simpleName = "OrderService",
                    kind = "CLASS",
                    sourcePath = "src/main/java/com/example/orders/OrderService.java",
                ),
            ),
            relations = listOf(
                RelationSliceFragment(
                    id = "rel:uses",
                    kind = "USES_TYPE",
                    fromSymbolId = "class:OrderService",
                    toSymbolId = "class:OrderRepository",
                ),
            ),
            resources = listOf(
                ResourceSliceFragment(
                    id = "resource:application.yml",
                    path = "src/main/resources/application.yml",
                    kind = "YAML",
                ),
            ),
        )

        store.write(key, fragment)

        assertEquals(fragment, store.read(key))
        assertTrue(cacheRoot.resolve("link-graph/architecture-index").exists())
        assertFalse(projectRoot.resolve("graphify-out").exists())
        assertFalse(projectRoot.resolve(".link-graph-cache").exists())
    }

    @Test
    fun corruptCacheReadReturnsNull() {
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val store = PersistentArchitectureIndexCacheStore(cacheRoot)
        val key = ArchitectureIndexFragmentCacheKey(
            projectLocationHash = "project",
            budgetHash = "budget",
            sliceId = "slice:orders",
            fileHash = "file",
            attachedJarFingerprint = null,
        )
        Files.createDirectories(cacheRoot.resolve("link-graph/architecture-index"))
        store.pathForTesting(key).writeText("{not json")

        assertNull(store.read(key))
    }

    @Test
    fun roundTripsFullFidelityFragmentFields() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val key = cacheKey("slice:orders")
        val fragment = ArchitectureIndexSliceFragment(
            sliceId = "slice:orders",
            symbols = listOf(
                SymbolSliceFragment(
                    id = "class:OrderService",
                    qualifiedName = "com.example.orders.OrderService",
                    simpleName = "OrderService",
                    kind = "CLASS",
                    sourcePath = "src/main/java/com/example/orders/OrderService.java",
                    sourceVirtualFileUrl = "file:///repo/src/main/java/com/example/orders/OrderService.java",
                    sourceStartLine = 3,
                    sourceEndLine = 30,
                    moduleName = "orders",
                    packageName = "com.example.orders",
                    classKind = "CLASS",
                    stereotype = "SERVICE",
                    testSource = true,
                    superClassName = "com.example.orders.BaseService",
                    interfaceNames = listOf("com.example.orders.OrderPort"),
                    docComment = "Order service docs.",
                ),
                SymbolSliceFragment(
                    id = "field:OrderService.clients",
                    qualifiedName = "com.example.orders.OrderService.clients",
                    simpleName = "clients",
                    kind = "FIELD",
                    ownerClassName = "com.example.orders.OrderService",
                    typeName = "java.util.List",
                    typeReferences = listOf(FieldTypeReferenceSliceFragment("com.example.orders.Client", "COLLECTION_ELEMENT")),
                ),
            ),
            resources = listOf(
                ResourceSliceFragment(
                    id = "resource:spi",
                    path = "src/main/resources/META-INF/services/com.example.Plugin",
                    kind = "SPI_SERVICE_FILE",
                    sourceVirtualFileUrl = "file:///repo/src/main/resources/META-INF/services/com.example.Plugin",
                ),
            ),
            serviceProviders = listOf(
                ServiceProviderSliceFragment(
                    serviceInterfaceName = "com.example.Plugin",
                    providerClassNames = listOf("com.example.DefaultPlugin"),
                    resourceId = "resource:spi",
                    resourcePath = "src/main/resources/META-INF/services/com.example.Plugin",
                    resourceKind = "SPI_SERVICE_FILE",
                ),
            ),
        )

        store.write(key, fragment)
        val restored = assertNotNull(store.read(key))

        assertEquals(fragment, restored)
    }

    private fun cacheKey(sliceId: String): ArchitectureIndexFragmentCacheKey =
        ArchitectureIndexFragmentCacheKey(
            projectLocationHash = "project",
            budgetHash = "budget",
            sliceId = sliceId,
            fileHash = "file",
        )
}

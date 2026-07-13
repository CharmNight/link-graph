package com.charmnight.linkgraph.architecture.memory

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistentArchitectureIndexCacheStoreTest {
    @Test
    fun startupRemovesOrphanFragmentsAndInterruptedTempFiles() {
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val storeRoot = cacheRoot.resolve("link-graph/architecture-index")
        Files.createDirectories(storeRoot)
        val orphan = storeRoot.resolve("orphan.json")
        val interrupted = storeRoot.resolve("fragment.json.123.tmp")
        Files.writeString(orphan, "{}")
        Files.writeString(interrupted, "partial")

        val store = PersistentArchitectureIndexCacheStore(cacheRoot)

        assertFalse(orphan.exists())
        assertFalse(interrupted.exists())
        assertEquals(2, store.lastCleanupDiagnostics.removedFileCount)
    }

    @Test
    fun corruptManifestClearsManagedFragments() {
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val key = cacheKey("slice:orders")
        val firstStore = PersistentArchitectureIndexCacheStore(cacheRoot)
        firstStore.write(key, ArchitectureIndexSliceFragment(sliceId = "slice:orders"))
        val fragmentPath = firstStore.pathForTesting(key)
        Files.writeString(cacheRoot.resolve("link-graph/architecture-index/_manifest.json"), "{broken")

        val recoveredStore = PersistentArchitectureIndexCacheStore(cacheRoot)

        assertFalse(fragmentPath.exists())
        assertNull(recoveredStore.read(key))
    }

    @Test
    fun startupRemovesEntriesFromOlderFragmentSchema() {
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val oldKey = cacheKey("slice:orders").copy(
            schemaVersion = ProjectSliceManifest.CURRENT_SCHEMA_VERSION - 1,
        )
        val firstStore = PersistentArchitectureIndexCacheStore(cacheRoot)
        firstStore.write(oldKey, ArchitectureIndexSliceFragment(sliceId = "slice:orders"))
        val oldPath = firstStore.pathForTesting(oldKey)

        PersistentArchitectureIndexCacheStore(cacheRoot)

        assertFalse(oldPath.exists())
    }

    @Test
    fun startupExpiresEntriesPastTtl() {
        var now = 1_000L
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val key = cacheKey("slice:orders")
        val firstStore = PersistentArchitectureIndexCacheStore(
            cacheRoot,
            ttlMillis = 100,
            clockMillis = { now },
        )
        firstStore.write(key, ArchitectureIndexSliceFragment(sliceId = "slice:orders"))
        val path = firstStore.pathForTesting(key)
        now += 101

        val expiredStore = PersistentArchitectureIndexCacheStore(
            cacheRoot,
            ttlMillis = 100,
            clockMillis = { now },
        )

        assertFalse(path.exists())
        assertNull(expiredStore.read(key))
    }

    @Test
    fun prunesLeastRecentlyUsedEntriesByCount() {
        var now = 1_000L
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val store = PersistentArchitectureIndexCacheStore(
            cacheRoot,
            maxEntries = 2,
            clockMillis = { now },
        )
        val first = cacheKey("slice:first")
        val second = cacheKey("slice:second")
        val third = cacheKey("slice:third")
        store.write(first, ArchitectureIndexSliceFragment(sliceId = "slice:first"))
        now += 1
        store.write(second, ArchitectureIndexSliceFragment(sliceId = "slice:second"))
        now += 1
        assertNotNull(store.read(first))
        now += 1
        store.write(third, ArchitectureIndexSliceFragment(sliceId = "slice:third"))

        assertNotNull(store.read(first))
        assertNull(store.read(second))
        assertNotNull(store.read(third))
    }

    @Test
    fun prunesOldestEntriesByTotalBytes() {
        val sizingRoot = Files.createTempDirectory("link-graph-ide-cache-size")
        val sizingStore = PersistentArchitectureIndexCacheStore(sizingRoot)
        val sampleKey = cacheKey("slice:sample")
        sizingStore.write(sampleKey, ArchitectureIndexSliceFragment(sliceId = "slice:sample"))
        val sampleBytes = Files.size(sizingStore.pathForTesting(sampleKey))

        var now = 1_000L
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val store = PersistentArchitectureIndexCacheStore(
            cacheRoot,
            maxTotalBytes = sampleBytes + 16,
            clockMillis = { now },
        )
        val first = cacheKey("slice:first")
        val second = cacheKey("slice:second")
        store.write(first, ArchitectureIndexSliceFragment(sliceId = "slice:first"))
        now += 1
        store.write(second, ArchitectureIndexSliceFragment(sliceId = "slice:second"))

        assertNull(store.read(first))
        assertNotNull(store.read(second))
    }

    @Test
    fun schemaVersionInvalidatesFragmentsWithoutJavaFallbackSuperTypes() {
        assertTrue(
            ProjectSliceManifest.CURRENT_SCHEMA_VERSION >= 5,
            "schema v4 fragments can miss Java fallback super types and must not be reused",
        )
    }

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
    fun oversizedCacheReadReturnsNullAndDeletesCacheFile() {
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
        val cacheFile = store.pathForTesting(key)
        Files.writeString(cacheFile, "x".repeat(8 * 1024 * 1024 + 1))

        assertNull(store.read(key))
        assertFalse(cacheFile.exists())
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

    @Test
    fun rejectsFragmentWhoseSliceIdConflictsWithCacheKey() {
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val store = PersistentArchitectureIndexCacheStore(cacheRoot)
        val key = cacheKey("slice:orders")

        assertFailsWith<IllegalArgumentException> {
            store.write(key, ArchitectureIndexSliceFragment(sliceId = "slice:payments"))
        }
        assertFalse(store.pathForTesting(key).exists())
    }

    @Test
    fun rejectsAndDeletesValidFragmentStoredUnderDifferentSliceKey() {
        val cacheRoot = Files.createTempDirectory("link-graph-ide-cache")
        val store = PersistentArchitectureIndexCacheStore(cacheRoot)
        val ordersKey = cacheKey("slice:orders")
        val paymentsKey = cacheKey("slice:payments")
        store.write(ordersKey, ArchitectureIndexSliceFragment(sliceId = ordersKey.sliceId))
        store.write(paymentsKey, ArchitectureIndexSliceFragment(sliceId = paymentsKey.sliceId))
        val ordersPath = store.pathForTesting(ordersKey)
        Files.copy(
            store.pathForTesting(paymentsKey),
            ordersPath,
            StandardCopyOption.REPLACE_EXISTING,
        )

        assertNull(store.read(ordersKey))
        assertFalse(ordersPath.exists())
    }

    private fun cacheKey(sliceId: String): ArchitectureIndexFragmentCacheKey =
        ArchitectureIndexFragmentCacheKey(
            projectLocationHash = "project",
            budgetHash = "budget",
            sliceId = sliceId,
            fileHash = "file",
        )
}

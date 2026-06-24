package com.charmnight.linkgraph.architecture.memory

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArchitectureIndexPersistentFragmentCacheTest {
    @Test
    fun restoresCompleteArchitectureIndexWhenEverySliceHitsPersistentCache() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = manifest(slice("slice:orders", "src/main/java/com/example/orders/OrderService.java", contentSha256 = "hash-orders"))
        val key = cacheKey("slice:orders")
        store.write(
            key,
            ArchitectureIndexSliceFragment(
                sliceId = "slice:orders",
                symbols = listOf(
                    SymbolSliceFragment(
                        id = "class:OrderService",
                        qualifiedName = "com.example.orders.OrderService",
                        simpleName = "OrderService",
                        kind = "CLASS",
                        sourcePath = "src/main/java/com/example/orders/OrderService.java",
                        packageName = "com.example.orders",
                    ),
                ),
            ),
        )

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = emptySet(),
            cacheKeyForSlice = { key },
            budget = JvmResolutionBudget(),
        )

        assertNotNull(result.index)
        assertEquals(1, result.hits)
        assertEquals(0, result.misses)
        assertEquals("com.example.orders.OrderService", result.index.symbolIndex.findClass("com.example.orders.OrderService")?.qualifiedName)
    }

    @Test
    fun doesNotRestoreCompleteIndexWhenAnySliceIsStaleOrMissing() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = manifest(slice("slice:orders", "src/main/java/com/example/orders/OrderService.java"))

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = setOf("slice:orders"),
            cacheKeyForSlice = { cacheKey("slice:orders") },
            budget = JvmResolutionBudget(),
        )

        assertNull(result.index)
        assertEquals(0, result.hits)
        assertEquals(1, result.misses)
        assertEquals(setOf("slice:orders"), result.missingSliceIds)
    }

    @Test
    fun treatsProjectSlicesWithoutContentHashAsPersistentCacheMiss() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = manifest(slice("slice:orders", "src/main/java/com/example/orders/OrderService.java", contentSha256 = null))
        val key = cacheKey("slice:orders")
        store.write(
            key,
            ArchitectureIndexSliceFragment(
                sliceId = "slice:orders",
                symbols = listOf(
                    SymbolSliceFragment(
                        id = "class:OrderService",
                        qualifiedName = "com.example.orders.OrderService",
                        simpleName = "OrderService",
                        kind = "CLASS",
                        sourcePath = "src/main/java/com/example/orders/OrderService.java",
                        packageName = "com.example.orders",
                    ),
                ),
            ),
        )

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = emptySet(),
            cacheKeyForSlice = { key },
            budget = JvmResolutionBudget(),
        )

        assertNull(result.index)
        assertEquals(0, result.hits)
        assertEquals(1, result.misses)
        assertEquals(setOf("slice:orders"), result.missingSliceIds)
    }

    @Test
    fun treatsProjectSlicesWithoutFilesAsPersistentCacheMiss() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = manifest(
            ProjectSlice(
                id = "slice:empty",
                moduleName = null,
                contentRoot = "/repo",
                sourceSet = "main",
                packagePrefix = "com.example.empty",
                kind = ProjectSliceKind.JVM_SOURCE.name,
                files = emptyList(),
            ),
        )
        val key = cacheKey("slice:empty")
        store.write(
            key,
            ArchitectureIndexSliceFragment(sliceId = "slice:empty"),
        )

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = emptySet(),
            cacheKeyForSlice = { key },
            budget = JvmResolutionBudget(),
        )

        assertNull(result.index)
        assertEquals(0, result.hits)
        assertEquals(1, result.misses)
        assertEquals(setOf("slice:empty"), result.missingSliceIds)
    }

    /**
     * 验证 partial rebuild 场景：
     * - 两个 slice A、B 都有缓存
     * - 把 A 标记为 stale（模拟源文件被删除）
     * - restoreCompleteIndex 应只把 B 加入 fragments，并报告 A 缺失
     * - 上游 rebuildFromPersistentFragments 用 rebuildSliceIds=[A] 过滤 cachedFragments，
     *   即使混入了 A 的旧 fragment（防御性测试）也不应被合并使用
     */
    @Test
    fun restoreReturnsOnlyNonStaleFragmentsAndReportsStaleAsMissing() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = ProjectSliceManifest(
            projectLocationHash = "project",
            slices = listOf(
                slice("slice:a", "src/main/java/com/example/a/OrderA.java", contentSha256 = "hash-a"),
                slice("slice:b", "src/main/java/com/example/b/OrderB.java", contentSha256 = "hash-b"),
            ),
        )
        val keyA = cacheKey("slice:a")
        val keyB = cacheKey("slice:b")
        store.write(
            keyA,
            ArchitectureIndexSliceFragment(
                sliceId = "slice:a",
                symbols = listOf(
                    SymbolSliceFragment(
                        id = "class:OrderA",
                        qualifiedName = "com.example.a.OrderA",
                        simpleName = "OrderA",
                        kind = "CLASS",
                        sourcePath = "src/main/java/com/example/a/OrderA.java",
                        packageName = "com.example.a",
                    ),
                ),
            ),
        )
        store.write(
            keyB,
            ArchitectureIndexSliceFragment(
                sliceId = "slice:b",
                symbols = listOf(
                    SymbolSliceFragment(
                        id = "class:OrderB",
                        qualifiedName = "com.example.b.OrderB",
                        simpleName = "OrderB",
                        kind = "CLASS",
                        sourcePath = "src/main/java/com/example/b/OrderB.java",
                        packageName = "com.example.b",
                    ),
                ),
            ),
        )

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = setOf("slice:a"),
            cacheKeyForSlice = { slice ->
                when (slice.id) {
                    "slice:a" -> keyA
                    "slice:b" -> keyB
                    else -> error("unexpected slice ${slice.id}")
                }
            },
            budget = JvmResolutionBudget(),
        )

        assertNull(result.index)
        assertEquals(1, result.hits)
        assertEquals(1, result.misses)
        assertEquals(setOf("slice:a"), result.missingSliceIds)
        // 关键断言：返回的 fragments 只包含未失效的 slice，不能让上游 merge 到 A 的幽灵符号
        assertEquals(listOf("slice:b"), result.fragments.map { it.sliceId })
    }

    /**
     * PersistentArchitectureIndexCacheStore.delete 应删除对应缓存文件，且对不存在的 key 幂等。
     */
    @Test
    fun storeDeleteRemovesCacheFileAndIsIdempotent() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val key = cacheKey("slice:a")
        store.write(key, ArchitectureIndexSliceFragment(sliceId = "slice:a"))

        // 删除前能读到
        assertNotNull(store.read(key))
        store.delete(key)
        assertNull(store.read(key))
        // 缓存文件已不在磁盘
        assertEquals(false, java.nio.file.Files.exists(store.pathForTesting(key)))
        // 再次 delete 不抛异常
        store.delete(key)
    }

    /**
     * manifest schemaVersion 与当前代码不一致时，restoreCompleteIndex 应直接返回 null 索引并报告全部 slice 缺失，
     * 避免把旧 schema fragment 合并出幽灵索引。
     */
    @Test
    fun treatsOutdatedSchemaVersionManifestAsFullCacheMiss() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val manifest = ProjectSliceManifest(
            projectLocationHash = "project",
            schemaVersion = ProjectSliceManifest.CURRENT_SCHEMA_VERSION - 1,
            slices = listOf(slice("slice:orders", "src/main/java/com/example/orders/OrderService.java")),
        )
        val key = cacheKey("slice:orders")
        store.write(
            key,
            ArchitectureIndexSliceFragment(
                sliceId = "slice:orders",
                symbols = listOf(
                    SymbolSliceFragment(
                        id = "class:OrderService",
                        qualifiedName = "com.example.orders.OrderService",
                        simpleName = "OrderService",
                        kind = "CLASS",
                        sourcePath = "src/main/java/com/example/orders/OrderService.java",
                        packageName = "com.example.orders",
                    ),
                ),
            ),
        )

        val result = ArchitectureIndexPersistentFragmentCache(store).restoreCompleteIndex(
            manifest = manifest,
            staleSliceIds = emptySet(),
            cacheKeyForSlice = { key },
            budget = JvmResolutionBudget(),
        )

        assertNull(result.index)
        assertEquals(0, result.hits)
        assertEquals(1, result.misses)
        assertEquals(setOf("slice:orders"), result.missingSliceIds)
    }

    /**
     * 不同 pluginVersion 的 cacheKey 应映射到不同的磁盘文件，避免开发模式下版本切换后读到旧缓存。
     */
    @Test
    fun differentPluginVersionsMapToDifferentCacheFiles() {
        val store = PersistentArchitectureIndexCacheStore(Files.createTempDirectory("link-graph-ide-cache"))
        val keyV1 = cacheKey("slice:orders").copy(pluginVersion = "0.1.0")
        val keyV2 = cacheKey("slice:orders").copy(pluginVersion = "0.2.0")

        store.write(keyV1, ArchitectureIndexSliceFragment(sliceId = "slice:orders"))

        // V1 写过、V2 没写过：两个 key 对应不同的磁盘文件
        assertNotNull(store.read(keyV1))
        assertNull(store.read(keyV2))
        assertEquals(false, java.nio.file.Files.exists(store.pathForTesting(keyV2)))
    }

    private fun manifest(slice: ProjectSlice): ProjectSliceManifest =
        ProjectSliceManifest(
            projectLocationHash = "project",
            slices = listOf(slice),
        )

    private fun slice(
        id: String,
        path: String,
        contentSha256: String? = "hash",
    ): ProjectSlice =
        ProjectSlice(
            id = id,
            moduleName = null,
            contentRoot = "/repo",
            sourceSet = "main",
            packagePrefix = "com.example.orders",
            kind = ProjectSliceKind.JVM_SOURCE.name,
            files = listOf(ProjectFileFingerprint(path, 1, 1, contentSha256)),
        )

    private fun cacheKey(sliceId: String): ArchitectureIndexFragmentCacheKey =
        ArchitectureIndexFragmentCacheKey(
            projectLocationHash = "project",
            budgetHash = "budget",
            sliceId = sliceId,
            fileHash = "file",
        )
}

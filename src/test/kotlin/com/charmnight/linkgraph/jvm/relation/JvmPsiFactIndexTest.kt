package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmPsiFactIndexTest : BasePlatformTestCase() {
    fun testBuildStoresQualifiedNameAndSignatureKeysInsteadOfPsiElements() {
        myFixture.addFileToProject(
            "src/main/java/com/example/index/IndexedService.java",
            """
            package com.example.index;
            public class IndexedService {
                public String echo(String input) { return input; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/index/IndexedKotlinService.kt",
            """
            package com.example.index
            class IndexedKotlinService {
                fun greet(name: String): String = name
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val psiFactIndex = JvmPsiFactIndex.build(project, symbolIndex)

        val javaSymbol = requireNotNull(symbolIndex.findClass("com.example.index.IndexedService"))
        val kotlinSymbol = requireNotNull(symbolIndex.findClass("com.example.index.IndexedKotlinService"))
        val methodSymbol = requireNotNull(
            symbolIndex.methodsBySignature.values.firstOrNull { method ->
                method.ownerClassName == "com.example.index.IndexedService" && method.simpleName == "echo"
            }
        )

        assertEquals(
            "com.example.index.IndexedService",
            psiFactIndex.classBySymbolId[javaSymbol.id],
        )
        assertEquals(
            "com.example.index.IndexedKotlinService",
            psiFactIndex.classBySymbolId[kotlinSymbol.id],
        )
        val methodLookup = requireNotNull(psiFactIndex.methodBySymbolId[methodSymbol.id])
        assertEquals("com.example.index.IndexedService", methodLookup.ownerQualifiedName)
        assertTrue(methodLookup.signature.startsWith("com.example.index.IndexedService.echo("))

        assertTrue(psiFactIndex.kotlinFileUrls.any { url -> url.endsWith("com/example/index/IndexedKotlinService.kt") })
    }

    fun testLookupPsiClassReResolvesFromQualifiedNameOnEachCall() {
        myFixture.addFileToProject(
            "src/main/java/com/example/lookup/LookupTarget.java",
            """
            package com.example.lookup;
            public class LookupTarget {
                public void run() {}
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val psiFactIndex = JvmPsiFactIndex.build(project, symbolIndex)
        val targetSymbol = requireNotNull(symbolIndex.findClass("com.example.lookup.LookupTarget"))

        val first = requireNotNull(psiFactIndex.lookupPsiClass(project, targetSymbol.id))
        val second = requireNotNull(psiFactIndex.lookupPsiClass(project, targetSymbol.id))

        assertEquals("com.example.lookup.LookupTarget", first.qualifiedName)
        assertEquals(
            "重解析同一 symbolId 时应返回与 VFS 当前状态一致的 PsiClass",
            first.qualifiedName,
            second.qualifiedName,
        )
    }

    fun testLookupPsiClassReturnsNullForUnknownSymbolId() {
        myFixture.addFileToProject(
            "src/main/java/com/example/lookup/Sentinel.java",
            """
            package com.example.lookup;
            public class Sentinel {}
            """.trimIndent(),
        )
        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val psiFactIndex = JvmPsiFactIndex.build(project, symbolIndex)

        assertNull(psiFactIndex.lookupPsiClass(project, "jvm:class:does-not-exist"))
    }

    fun testLookupPsiMethodReResolvesFromOwnerAndSignature() {
        myFixture.addFileToProject(
            "src/main/java/com/example/lookup/MethodHost.java",
            """
            package com.example.lookup;
            public class MethodHost {
                public String pick(String value) { return value; }
            }
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val psiFactIndex = JvmPsiFactIndex.build(project, symbolIndex)
        val methodSymbol = requireNotNull(
            symbolIndex.methodsBySignature.values.firstOrNull { method ->
                method.ownerClassName == "com.example.lookup.MethodHost" && method.simpleName == "pick"
            }
        )

        val resolved = requireNotNull(psiFactIndex.lookupPsiMethod(project, methodSymbol.id))
        assertEquals("pick", resolved.name)
        assertEquals("com.example.lookup.MethodHost", resolved.containingClass?.qualifiedName)
    }

    fun testLookupPsiMethodReturnsNullForUnknownSymbolId() {
        myFixture.addFileToProject(
            "src/main/java/com/example/lookup/EmptyHost.java",
            """
            package com.example.lookup;
            public class EmptyHost {}
            """.trimIndent(),
        )
        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val psiFactIndex = JvmPsiFactIndex.build(project, symbolIndex)

        assertNull(psiFactIndex.lookupPsiMethod(project, "jvm:method:does-not-exist"))
    }

    fun testLookupKotlinFilesReResolvesFromUrlsOnEachCall() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/lookup/KtFileOne.kt",
            """
            package com.example.lookup
            class KtFileOne
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/lookup/KtFileTwo.kt",
            """
            package com.example.lookup
            class KtFileTwo
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val psiFactIndex = JvmPsiFactIndex.build(project, symbolIndex)

        val first = psiFactIndex.lookupKotlinFiles(project)
        val second = psiFactIndex.lookupKotlinFiles(project)

        assertTrue(first.any { file -> file.name == "KtFileOne.kt" })
        assertTrue(first.any { file -> file.name == "KtFileTwo.kt" })
        assertEquals(
            "每次 lookup 都应基于当前 VFS 重解析，文件数量保持稳定",
            first.map { it.name }.sorted(),
            second.map { it.name }.sorted(),
        )
    }
}

package com.charmnight.linkgraph.application.debug

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebugMethodSignatureLocatorDiagnosticsTest : BasePlatformTestCase() {
    fun testCollectLookupDiagnosticsReportsProjectClassAndFile() {
        myFixture.addFileToProject(
            "src/main/java/com/example/ScannerImpl.java",
            """
                package com.example;

                import java.util.List;

                public class ScannerImpl {
                    protected List<String> detectPackageManagers() {
                        return List.of();
                    }
                }
            """.trimIndent(),
        )

        val diagnostics = DebugMethodSignatureLocator.collectLookupDiagnostics(
            project,
            "com.example.ScannerImpl.detectPackageManagers():java.util.List",
        )
        val lookup = requireNotNull(diagnostics)

        assertEquals("com.example.ScannerImpl", lookup.projectScope.qualifiedHit)
        assertTrue(lookup.projectScope.shortNameCount >= 1)
        assertTrue(lookup.filenameHits.any { hit -> hit.path.endsWith("/ScannerImpl.java") })
    }
}

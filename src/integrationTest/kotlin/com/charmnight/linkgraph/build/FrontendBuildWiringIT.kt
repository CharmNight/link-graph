package com.charmnight.linkgraph.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontendBuildWiringIT {
    @Test
    fun buildScriptSplitsFrontendTasksAndDetachesBackendTestsFromFrontendBuild() {
        val buildScript = Path.of("build.gradle.kts")
        assertTrue(Files.exists(buildScript), "Expected build.gradle.kts at project root")

        val scriptText = Files.readString(buildScript)

        assertTrue(scriptText.contains("val frontendInstall by tasks.registering"))
        assertTrue(scriptText.contains("val frontendTest by tasks.registering"))
        assertTrue(scriptText.contains("val frontendBuild by tasks.registering"))
        assertTrue(scriptText.contains("val frontendPackResources by tasks.registering(Sync::class)"))
        assertTrue(scriptText.contains("dependsOn(frontendTest)"))
        assertTrue(scriptText.contains("named(\"prepareSandbox\")"))
        assertTrue(scriptText.contains("dependsOn(frontendPackResources)"))
        assertFalse(scriptText.contains("dependsOn(buildFrontend)"))
    }
}

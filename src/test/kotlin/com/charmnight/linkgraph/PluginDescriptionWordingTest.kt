package com.charmnight.linkgraph

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class PluginDescriptionWordingTest {
    @Test
    fun pluginDescriptionDoesNotKeepObsoleteWording() {
        val buildGradle = Files.readString(
            Path.of(System.getProperty("user.dir")).resolve("build.gradle.kts"),
        )

        assertFalse(
            buildGradle.contains("可复核"),
            "插件描述不应继续保留“可复核”口径。",
        )
    }
}

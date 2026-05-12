package com.charmnight.linkgraph

import com.charmnight.linkgraph.testing.*

import java.util.Properties
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkGraphBundlePropertiesTest {
    @Test
    fun pluginDescriptionUsesQaWordingInsteadOfObsoleteWording() {
        val properties = Properties()
        val inputStream = javaClass.classLoader.getResourceAsStream("messages/LinkGraphBundle.properties")
        assertNotNull(inputStream)
        inputStream.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use(properties::load)
        }

        val description = properties.getProperty("plugin.description")
        assertNotNull(description)
        assertFalse(description.contains("可审计"))
        assertTrue(description.contains("可追溯") || description.contains("可核对"))
    }
}

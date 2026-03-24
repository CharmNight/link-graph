package com.charmnight.linkgraph

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PluginBootstrapTest {
    @Test
    fun pluginMetadataContainsExpectedPluginId() {
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
        assertNotNull("Expected META-INF/plugin.xml on the test classpath", stream)

        val document = stream!!.use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }
        val idNodes = document.getElementsByTagName("id")
        assertEquals("Expected exactly one <id> in plugin.xml", 1, idNodes.length)
        assertEquals("com.charmnight.linkgraph", idNodes.item(0).textContent.trim())
    }
}

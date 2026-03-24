package com.charmnight.linkgraph

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PluginBootstrapTest {
    @Test
    fun pluginMetadataContainsExpectedBootstrapRegistrations() {
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
        assertNotNull("Expected META-INF/plugin.xml on the test classpath", stream)

        val document = stream!!.use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }
        val idNodes = document.getElementsByTagName("id")
        assertEquals("Expected exactly one <id> in plugin.xml", 1, idNodes.length)
        assertEquals("com.charmnight.linkgraph", idNodes.item(0).textContent.trim())

        val toolWindow = firstElementByTagNameAndAttribute(
            document,
            "toolWindow",
            "id",
            "Link Graph",
        )
        assertNotNull("Expected placeholder Link Graph tool window registration", toolWindow)
        assertEquals(
            "com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowFactory",
            toolWindow!!.getAttribute("factoryClass"),
        )

        val action = firstElementByTagNameAndAttribute(
            document,
            "action",
            "id",
            "com.charmnight.linkgraph.OpenLinkGraphAction",
        )
        assertNotNull("Expected placeholder Open Link Graph action registration", action)
        assertEquals(
            "com.charmnight.linkgraph.actions.OpenLinkGraphAction",
            action!!.getAttribute("class"),
        )

        val classLoader = javaClass.classLoader
        assertNotNull(
            "Expected placeholder tool window factory class on the classpath",
            classLoader.loadClass("com.charmnight.linkgraph.toolwindow.LinkGraphToolWindowFactory"),
        )
        assertNotNull(
            "Expected placeholder action class on the classpath",
            classLoader.loadClass("com.charmnight.linkgraph.actions.OpenLinkGraphAction"),
        )
    }

    @Test
    fun pluginBuildHookTargetsWebWorkspace() {
        val buildScript = Path.of("build.gradle.kts")
        assertTrue("Expected build.gradle.kts at project root", Files.exists(buildScript))

        val scriptText = Files.readString(buildScript)
        assertTrue("Expected frontend hook to target web/package.json", scriptText.contains("web/package.json"))
        assertTrue("Expected frontend hook to work from the web directory", scriptText.contains("workingDir = file(\"web\")"))
        assertTrue("Did not expect legacy frontend/ path in hook", !scriptText.contains("frontend/package.json"))
    }

    private fun firstElementByTagNameAndAttribute(
        document: org.w3c.dom.Document,
        tagName: String,
        attributeName: String,
        attributeValue: String,
    ): Element? {
        val nodes = document.getElementsByTagName(tagName)
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.getAttribute(attributeName) == attributeValue) {
                return node
            }
        }
        return null
    }
}

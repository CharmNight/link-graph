package com.charmnight.linkgraph

import com.charmnight.linkgraph.testing.*

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.reflect.jvm.javaConstructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.intellij.openapi.project.Project

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
        assertEquals("Did not expect plugin.xml to depend on a plugin descriptor resource bundle", 0, document.getElementsByTagName("resource-bundle").length)

        val toolWindow = firstElementByTagNameAndAttribute(
            document,
            "toolWindow",
            "id",
            "链路图",
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

        val appendAction = firstElementByTagNameAndAttribute(
            document,
            "action",
            "id",
            "com.charmnight.linkgraph.AddCurrentMethodToGraphAction",
        )
        assertNotNull("Expected add-current-method action registration", appendAction)
        assertEquals(
            "com.charmnight.linkgraph.actions.AddCurrentMethodToGraphAction",
            appendAction!!.getAttribute("class"),
        )

        val classDiagramAction = firstElementByTagNameAndAttribute(
            document,
            "action",
            "id",
            "com.charmnight.linkgraph.OpenCurrentClassDiagramAction",
        )
        assertNotNull("Expected open-current-class-diagram action registration", classDiagramAction)
        assertEquals(
            "com.charmnight.linkgraph.actions.OpenCurrentClassDiagramAction",
            classDiagramAction!!.getAttribute("class"),
        )

        val settingsAction = firstElementByTagNameAndAttribute(
            document,
            "action",
            "id",
            "com.charmnight.linkgraph.OpenLinkGraphSettingsAction",
        )
        assertNotNull("Expected Link Graph settings action registration", settingsAction)
        assertEquals(
            "com.charmnight.linkgraph.actions.OpenLinkGraphSettingsAction",
            settingsAction!!.getAttribute("class"),
        )

        val toolsGroup = firstElementByTagNameAndAttribute(
            document,
            "group",
            "id",
            "com.charmnight.linkgraph.ToolsGroup",
        )
        assertNotNull("Expected visible Link Graph tools group registration", toolsGroup)
        assertEquals("链路图", toolsGroup!!.getAttribute("text"))

        val configurable = firstElementByTagNameAndAttribute(
            document,
            "applicationConfigurable",
            "instance",
            "com.charmnight.linkgraph.settings.LinkGraphSettingsConfigurable",
        )
        assertNotNull("Expected Link Graph settings configurable registration", configurable)
        assertEquals("tools", configurable!!.getAttribute("parentId"))

        val debugStartupActivity = firstElementByTagNameAndAttribute(
            document,
            "postStartupActivity",
            "implementation",
            "com.charmnight.linkgraph.toolwindow.LinkGraphDebugStartupActivity",
        )
        assertTrue(
            "Did not expect default published plugin.xml to register debug startup activity",
            debugStartupActivity == null,
        )
        val debugPackageStartupActivity = firstElementByTagNameAndAttribute(
            document,
            "postStartupActivity",
            "implementation",
            "com.charmnight.linkgraph.toolwindow.debug.LinkGraphDebugStartupActivity",
        )
        assertNotNull(
            "Expected debug startup activity registration; it is inert unless LINKGRAPH_DEBUG_* is set",
            debugPackageStartupActivity,
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
        assertNotNull(
            "Expected settings action class on the classpath",
            classLoader.loadClass("com.charmnight.linkgraph.actions.OpenLinkGraphSettingsAction"),
        )
        assertNotNull(
            "Expected append action class on the classpath",
            classLoader.loadClass("com.charmnight.linkgraph.actions.AddCurrentMethodToGraphAction"),
        )
        assertNotNull(
            "Expected class diagram action class on the classpath",
            classLoader.loadClass("com.charmnight.linkgraph.actions.OpenCurrentClassDiagramAction"),
        )
        assertNotNull(
            "Expected settings configurable class on the classpath",
            classLoader.loadClass("com.charmnight.linkgraph.settings.LinkGraphSettingsConfigurable"),
        )
        assertNotNull(
            "Expected settings service class on the classpath",
            classLoader.loadClass("com.charmnight.linkgraph.settings.LinkGraphSettingsService"),
        )
    }

    @Test
    fun pluginDeclaresLinkGraphExtensionPointsAndProductionReaders() {
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
        assertNotNull("Expected META-INF/plugin.xml on the test classpath", stream)

        val document = stream!!.use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }
        val llmGateway = firstElementByTagNameAndAttribute(
            document,
            "extensionPoint",
            "name",
            "linkGraph.llmGateway",
        )
        val projectionProvider = firstElementByTagNameAndAttribute(
            document,
            "extensionPoint",
            "name",
            "linkGraph.projectionProvider",
        )

        assertNotNull("Expected linkGraph.llmGateway extension point", llmGateway)
        assertEquals(
            "com.charmnight.linkgraph.llm.LlmGatewayContributor",
            llmGateway!!.getAttribute("interface"),
        )
        assertNotNull("Expected linkGraph.projectionProvider extension point", projectionProvider)
        assertEquals(
            "com.charmnight.linkgraph.projection.ProjectionProvider",
            projectionProvider!!.getAttribute("interface"),
        )

        val llmRoot = Files.readString(Path.of("src/main/kotlin/com/charmnight/linkgraph/llm/LlmGatewayCompositionRoot.kt"))
        assertTrue(
            "LLM composition root must read the documented linkGraph.llmGateway EP",
            llmRoot.contains("ExtensionPointName.create(\"linkGraph.llmGateway\")"),
        )
        val projectionExtensions = Files.readString(
            Path.of("src/main/kotlin/com/charmnight/linkgraph/projection/ProjectionProviderExtensions.kt"),
        )
        assertTrue(
            "Projection provider registry must read the documented linkGraph.projectionProvider EP",
            projectionExtensions.contains("ExtensionPointName.create(\"linkGraph.projectionProvider\")"),
        )
    }

    @Test
    fun pluginBuildHookTargetsWebWorkspace() {
        val buildScript = Path.of("build.gradle.kts")
        assertTrue("Expected build.gradle.kts at project root", Files.exists(buildScript))

        val scriptText = Files.readString(buildScript)
        assertTrue("Expected frontend hook to target the web workspace", scriptText.contains("val webDir = layout.projectDirectory.dir(\"web\")"))
        assertTrue("Expected frontend hook to target package.json inside the web workspace", scriptText.contains("webPackageJson = webDir.file(\"package.json\")"))
        assertTrue("Expected frontend hook to define split frontend tasks", scriptText.contains("val frontendInstall by tasks.registering"))
        assertTrue("Expected frontend hook to work from the web directory", scriptText.contains("workingDir = webDir.asFile"))
        assertTrue("Did not expect legacy frontend/ path in hook", !scriptText.contains("frontend/package.json"))
    }

    @Test
    fun projectComponentsKeepsSupportedIntellijConstructorSignature() {
        val constructors = GraphEditorApplicationService::class.constructors
            .mapNotNull { it.javaConstructor }
            .map { constructor -> constructor.parameterTypes.toList() }

        assertTrue(
            "Expected GraphEditorApplicationService to expose a supported IntelliJ constructor signature",
            constructors.any { parameters ->
                parameters.size == 1 && parameters[0] == Project::class.java
            },
        )
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

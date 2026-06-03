package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class SpringConfigBindingRelationResolverTest : BasePlatformTestCase() {
    fun testConfigurationPropertiesAnnotationLiteralBindsToApplicationResource() {
        myFixture.addFileToProject(
            "src/main/java/com/example/config/OrderProperties.java",
            """
            package com.example.config;

            @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "orders")
            public class OrderProperties {
                private String endpoint;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/application.yml",
            """
            orders:
              endpoint: https://example.test
            """.trimIndent(),
        )

        val symbolIndex = JvmSymbolIndexBuilder(project).build()
        val relationIndex = JvmRelationResolverRegistry().resolveAll(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
            ),
        )
        val propertiesClass = requireNotNull(symbolIndex.findClass("com.example.config.OrderProperties"))
        val resource = requireNotNull(symbolIndex.findResource("src/main/resources/application.yml"))
        val summary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.metadata}"
        }

        assertTrue(
            relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.RESOURCE_BINDS &&
                    relation.fromSymbolId == propertiesClass.id &&
                    relation.toSymbolId == resource.id &&
                    relation.metadata["framework"] == "spring-boot" &&
                    relation.metadata["config.prefix"] == "orders" &&
                    relation.metadata["config.resourcePath"] == "src/main/resources/application.yml" &&
                    relation.metadata["spring.annotationEvidence"] == "ANNOTATION_LITERAL"
            },
            summary,
        )
    }
}

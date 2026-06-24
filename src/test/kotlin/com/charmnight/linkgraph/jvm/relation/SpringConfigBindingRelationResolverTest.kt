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

    /**
     * 嵌套 YAML：prefix=`spring.orders` 应能匹配 `spring:\n  orders:\n    endpoint: ...` 形式。
     * 旧实现的单行正则 `^\s*spring\.orders\s*:` 永远命中不到嵌套结构。
     */
    fun testNestedYamlPrefixBindsConfigurationPropertiesToResource() {
        myFixture.addFileToProject(
            "src/main/java/com/example/config/OrderProperties.java",
            """
            package com.example.config;

            @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "spring.orders")
            public class OrderProperties {
                private String endpoint;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/resources/application.yml",
            """
            spring:
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

        assertTrue(
            relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.RESOURCE_BINDS &&
                    relation.fromSymbolId == propertiesClass.id &&
                    relation.toSymbolId == resource.id &&
                    relation.metadata["config.prefix"] == "spring.orders"
            },
            relationIndex.relations.joinToString("\n") { "${it.kind} ${it.fromSymbolId} -> ${it.toSymbolId} ${it.metadata}" },
        )
    }

    /**
     * properties 文件：prefix=`orders` 应能匹配 `orders.endpoint = ...`。
     */
    fun testPropertiesFilePrefixBindsConfigurationPropertiesToResource() {
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
            "src/main/resources/application.properties",
            """
            orders.endpoint=https://example.test
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
        val resource = requireNotNull(symbolIndex.findResource("src/main/resources/application.properties"))

        assertTrue(
            relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.RESOURCE_BINDS &&
                    relation.fromSymbolId == propertiesClass.id &&
                    relation.toSymbolId == resource.id &&
                    relation.metadata["config.prefix"] == "orders"
            },
            relationIndex.relations.joinToString("\n") { "${it.kind} ${it.fromSymbolId} -> ${it.toSymbolId} ${it.metadata}" },
        )
    }

    /**
     * @Value("${foo.bar}") 注解的字段应能绑定到声明了 `foo.bar` 键的资源。
     */
    fun testValueAnnotatedFieldBindsToApplicationResource() {
        myFixture.addFileToProject(
            "src/main/java/com/example/service/OrderService.java",
            """
            package com.example.service;

            public class OrderService {
                @org.springframework.beans.factory.annotation.Value("${"$"}{orders.endpoint}")
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
        val service = requireNotNull(symbolIndex.findClass("com.example.service.OrderService"))
        val resource = requireNotNull(symbolIndex.findResource("src/main/resources/application.yml"))

        assertTrue(
            relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.RESOURCE_BINDS &&
                    relation.fromSymbolId == service.id &&
                    relation.toSymbolId == resource.id &&
                    relation.metadata["config.placeholder"] == "orders.endpoint" &&
                    relation.metadata["spring.annotation"] == "org.springframework.beans.factory.annotation.Value"
            },
            relationIndex.relations.joinToString("\n") { "${it.kind} ${it.fromSymbolId} -> ${it.toSymbolId} ${it.metadata}" },
        )
    }
}

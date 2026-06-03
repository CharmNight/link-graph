package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class SpringEndpointRelationResolverTest : BasePlatformTestCase() {
    fun testSpringMvcAnnotationLiteralsProduceEndpointRelations() {
        myFixture.addFileToProject(
            "src/main/java/com/example/http/OrderController.java",
            """
            package com.example.http;

            @org.springframework.web.bind.annotation.RestController
            @org.springframework.web.bind.annotation.RequestMapping("/orders")
            public class OrderController {
                @org.springframework.web.bind.annotation.GetMapping("/{id}")
                public String getOrder(String id) {
                    return id;
                }
            }
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
        val controller = requireNotNull(symbolIndex.findClass("com.example.http.OrderController"))
        val summary = relationIndex.relations.joinToString("\n") { relation ->
            "${relation.kind} ${relation.fromSymbolId} -> ${relation.toSymbolId} ${relation.metadata}"
        }

        assertTrue(
            relationIndex.relations.any { relation ->
                relation.kind == JvmRelationKind.SPRING_ROUTES_TO &&
                    relation.fromSymbolId == controller.id &&
                    relation.metadata["framework"] == "spring-web" &&
                    relation.metadata["http.method"] == "GET" &&
                    relation.metadata["http.path"] == "/orders/{id}" &&
                    relation.metadata["spring.controllerClass"] == "com.example.http.OrderController" &&
                    relation.metadata["spring.handlerMethod"] ==
                    "com.example.http.OrderController.getOrder(java.lang.String):java.lang.String" &&
                    relation.metadata["spring.annotationEvidence"] == "ANNOTATION_LITERAL"
            },
            summary,
        )
    }
}

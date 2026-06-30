package com.charmnight.linkgraph.jvm.relation

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 覆盖 HttpEndpointRelationExtractor.requestMethod 对 Spring `@RequestMapping(method = ...)`
 * 各种写法的解析，包括：
 * - 简单枚举引用 `RequestMethod.GET`
 * - 全限定名 `org.springframework.web.bind.annotation.RequestMethod.DELETE`
 * - 数组形式 `{RequestMethod.GET, RequestMethod.POST}`
 * - 组合注解（@GetMapping 等）
 *
 * 旧实现用 `text.contains("GET")` 子串匹配，会漏掉全限定名形式、误判无关标识符；
 * 新实现通过 PSI 引用解析到 PsiEnumConstant 后再读名字。
 */
class HttpEndpointRelationExtractorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // 在测试夹具里桩出 Spring 的 RequestMethod 枚举，使 `method = RequestMethod.GET`
        // 这样的引用能被 PSI 解析到 PsiEnumConstant，从而走真实的 resolve 路径。
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/RequestMethod.java",
            """
            package org.springframework.web.bind.annotation;
            public enum RequestMethod {
                GET, POST, PUT, DELETE, PATCH
            }
            """.trimIndent(),
        )
        // 同时桩出 RequestMapping 注解（避免依赖 spring-web jar）
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/RequestMapping.java",
            """
            package org.springframework.web.bind.annotation;
            public @interface RequestMapping {
                String[] value() default {};
                String[] path() default {};
                RequestMethod[] method() default {};
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "org/springframework/web/bind/annotation/RestController.java",
            """
            package org.springframework.web.bind.annotation;
            public @interface RestController {}
            """.trimIndent(),
        )
    }

    fun testRequestMappingWithSimpleEnumReferenceResolvesMethod() {
        val method = addControllerMethod(
            """
            @org.springframework.web.bind.annotation.RequestMapping(value = "/orders", method = RequestMethod.POST)
            public String create() { return ""; }
            """.trimIndent(),
        )

        assertEquals("POST", HttpEndpointRelationExtractor.requestMethod(method))
    }

    fun testRequestMappingWithFullyQualifiedEnumResolvesMethod() {
        val method = addControllerMethod(
            """
            @org.springframework.web.bind.annotation.RequestMapping(
                value = "/orders",
                method = org.springframework.web.bind.annotation.RequestMethod.DELETE
            )
            public String delete() { return ""; }
            """.trimIndent(),
        )

        assertEquals("DELETE", HttpEndpointRelationExtractor.requestMethod(method))
    }

    fun testRequestMappingWithArrayFormPicksFirstMethod() {
        val method = addControllerMethod(
            """
            @org.springframework.web.bind.annotation.RequestMapping(
                value = "/orders",
                method = { RequestMethod.GET, RequestMethod.POST }
            )
            public String fetch() { return ""; }
            """.trimIndent(),
        )

        assertEquals("GET", HttpEndpointRelationExtractor.requestMethod(method))
    }

    fun testGetMappingComposedAnnotationResolvesToGet() {
        val method = addControllerMethod(
            """
            @org.springframework.web.bind.annotation.GetMapping("/orders")
            public String fetch() { return ""; }
            """.trimIndent(),
        )

        assertEquals("GET", HttpEndpointRelationExtractor.requestMethod(method))
    }

    fun testUnrelatedIdentifierContainingGetSubstringIsNotMisreadAsGet() {
        // 旧实现的 text.contains("GET") 会把 `GETSomethingElse` 误判为 GET；
        // 由于这里引用解析不到 PsiEnumConstant，应返回 null
        myFixture.addFileToProject(
            "com/example/NotAnEnum.java",
            """
            package com.example;
            public final class NotAnEnum {
                public static final String GETNotAnHttpMethod = "x";
            }
            """.trimIndent(),
        )
        val method = addControllerMethod(
            """
            @org.springframework.web.bind.annotation.RequestMapping(
                value = "/orders",
                method = com.example.NotAnEnum.GETNotAnHttpMethod
            )
            public String fetch() { return ""; }
            """.trimIndent(),
        )

        assertNull(HttpEndpointRelationExtractor.requestMethod(method))
    }

    /**
     * 自定义组合注解（meta-annotation）：`@MyGetMapping` 元标注 `@GetMapping`，
     * 控制器方法用 `@MyGetMapping` 也应被识别为 GET。
     */
    fun testComposedGetMappingMetaAnnotationIsRecognizedAsGet() {
        myFixture.addFileToProject(
            "com/example/MyGetMapping.java",
            """
            package com.example;
            @org.springframework.web.bind.annotation.GetMapping
            public @interface MyGetMapping {}
            """.trimIndent(),
        )
        val method = addControllerMethod(
            """
            @com.example.MyGetMapping("/orders")
            public String fetch() { return ""; }
            """.trimIndent(),
        )

        assertEquals("GET", HttpEndpointRelationExtractor.requestMethod(method))
    }

    private fun addControllerMethod(methodSource: String): PsiMethod {
        myFixture.addFileToProject(
            "com/example/OrdersController.java",
            """
            package com.example;
            import org.springframework.web.bind.annotation.RequestMethod;
            @org.springframework.web.bind.annotation.RestController
            public class OrdersController {
                $methodSource
            }
            """.trimIndent(),
        )
        val facade = JavaPsiFacade.getInstance(project)
        val cls = requireNotNull(
            facade.findClass("com.example.OrdersController", GlobalSearchScope.projectScope(project)),
        )
        return cls.methods.single()
    }
}

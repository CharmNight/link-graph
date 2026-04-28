package com.charmnight.linkgraph.semantic.subject

import com.charmnight.linkgraph.testing.*

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ResourceSubjectDecoderTest : BasePlatformTestCase() {
    fun testLocatePropertiesConfigItem() {
        myFixture.configureByText(
            "application.properties",
            """
                order.submit.han<caret>dler=com.example.OrderService#submit
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.CONFIG_ITEM, resourceHandle.kind)
        assertEquals("order.submit.handler", resourceHandle.displayName)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
    }

    fun testLocateNestedYamlConfigItem() {
        myFixture.configureByText(
            "application.yml",
            """
                order:
                  submit:
                    han<caret>dler: com.example.OrderService.submit(java.lang.String):void
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.CONFIG_ITEM, resourceHandle.kind)
        assertEquals("order.submit.handler", resourceHandle.displayName)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
        assertEquals(listOf("java.lang.String"), resourceHandle.resourceAnchor?.parameterTypeNames)
        assertEquals("void", resourceHandle.resourceAnchor?.returnTypeName)
    }

    fun testLocateYamlListItemConfigItem() {
        myFixture.configureByText(
            "application.yml",
            """
                order:
                  handlers:
                    - type: primary
                      r<caret>ef: com.example.OrderService.submit(java.lang.Long):java.lang.String
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.CONFIG_ITEM, resourceHandle.kind)
        assertEquals("order.handlers[0].ref", resourceHandle.displayName)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
        assertEquals(listOf("java.lang.Long"), resourceHandle.resourceAnchor?.parameterTypeNames)
        assertEquals("java.lang.String", resourceHandle.resourceAnchor?.returnTypeName)
    }

    fun testLocateYamlNestedMapInsideListItem() {
        myFixture.configureByText(
            "application.yml",
            """
                order:
                  handlers:
                    - channel:
                        r<caret>ef: com.example.OrderService.submit(java.lang.Long):java.lang.String
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.CONFIG_ITEM, resourceHandle.kind)
        assertEquals("order.handlers[0].channel.ref", resourceHandle.displayName)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
        assertEquals(listOf("java.lang.Long"), resourceHandle.resourceAnchor?.parameterTypeNames)
        assertEquals("java.lang.String", resourceHandle.resourceAnchor?.returnTypeName)
    }

    fun testLocateQuotedYamlKeys() {
        myFixture.configureByText(
            "application.yml",
            """
                "order-flow":
                  "submit-handler":
                    r<caret>ef: com.example.OrderService#submit
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.CONFIG_ITEM, resourceHandle.kind)
        assertEquals("order-flow.submit-handler.ref", resourceHandle.displayName)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
    }

    fun testLocateMyBatisStatement() {
        myFixture.configureByText(
            "UserMapper.xml",
            """
                <mapper namespace="com.example.UserMapper">
                  <select id="select<caret>User" resultType="com.example.User">
                    select * from user
                  </select>
                </mapper>
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.MYBATIS_STATEMENT, resourceHandle.kind)
        assertEquals("SQL UserMapper.selectUser", resourceHandle.displayName)
        assertEquals("com.example.UserMapper", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("selectUser", resourceHandle.resourceAnchor?.methodName)
    }

    fun testLocateXmlPropertyConfigItem() {
        myFixture.configureByText(
            "spring.xml",
            """
                <beans>
                  <bean id="orderService" class="com.example.OrderService">
                    <property name="han<caret>dler" value="com.example.OrderService.submit(java.lang.String):void" />
                  </bean>
                </beans>
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.CONFIG_ITEM, resourceHandle.kind)
        assertEquals("handler", resourceHandle.displayName)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
        assertEquals(listOf("java.lang.String"), resourceHandle.resourceAnchor?.parameterTypeNames)
        assertEquals("void", resourceHandle.resourceAnchor?.returnTypeName)
    }

    fun testLocateXmlResourceFallback() {
        myFixture.configureByText(
            "spring.xml",
            """
                <beans>
                  <bean id="orderSer<caret>vice" class="com.example.OrderService" />
                </beans>
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.XML_RESOURCE, resourceHandle.kind)
        assertEquals("spring.xml", resourceHandle.displayName)
        assertTrue((resourceHandle.attributes["path"] as? String)?.endsWith("spring.xml") == true)
    }

    fun testLocateMarkdownDocumentPage() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                这里会调用 com.example.OrderService#sub<caret>mit
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.MARKDOWN_PAGE, resourceHandle.kind)
        assertEquals("order-flow.md", resourceHandle.displayName)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
    }

    fun testLocateMarkdownMethodSignatureReference() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                这里会调用 com.example.OrderService.sub<caret>mit(java.lang.String):void
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.MARKDOWN_PAGE, resourceHandle.kind)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
        assertEquals(listOf("java.lang.String"), resourceHandle.resourceAnchor?.parameterTypeNames)
        assertEquals("void", resourceHandle.resourceAnchor?.returnTypeName)
    }

    fun testLocateMarkdownDottedMethodReference() {
        myFixture.configureByText(
            "order-flow.md",
            """
                # Order Flow
                这里会调用 com.example.OrderService.sub<caret>mit
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.MARKDOWN_PAGE, resourceHandle.kind)
        assertEquals("com.example.OrderService", resourceHandle.resourceAnchor?.ownerName)
        assertEquals("submit", resourceHandle.resourceAnchor?.methodName)
        assertEquals(null, resourceHandle.resourceAnchor?.parameterTypeNames)
        assertEquals(null, resourceHandle.resourceAnchor?.returnTypeName)
    }

    fun testLocateSqlFile() {
        myFixture.configureByText(
            "order_query.sql",
            """
                select * fr<caret>om orders where id = ?
            """.trimIndent(),
        )

        val handle = CaretSubjectLocator().locate(project, myFixture.editor)

        assertNotNull(handle)
        val resourceHandle = assertInstanceOf(handle, ResourceSubjectHandle::class.java)
        assertEquals(ResourceSubjectKind.SQL_FILE, resourceHandle.kind)
        assertEquals("order_query.sql", resourceHandle.displayName)
    }
}

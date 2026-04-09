package com.charmnight.linkgraph.testing

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestFixtureResourcesTest {
    @Test
    fun loadsJavaFixtureFromClasspath() {
        val resource = javaClass.classLoader.getResource("fixtures/java/simple/SimpleCallChain.java")

        assertNotNull(resource, "Java fixture should be exposed from src/test/resources")
        assertTrue(resource.readText().contains("class SimpleCallChain"))
    }

    @Test
    fun loadsKotlinFixtureFromClasspath() {
        val resource = javaClass.classLoader.getResource("fixtures/kotlin/kotlin/KotlinCallChain.kt")

        assertNotNull(resource, "Kotlin fixture should be exposed from src/test/resources")
        assertTrue(resource.readText().contains("class KotlinOrderFlow"))
    }

    @Test
    fun loadsFixtureResourcesFromClasspath() {
        val resource = javaClass.classLoader.getResource("fixtures/resources/mybatis/UserMapper.xml")

        assertNotNull(resource, "Resource fixture should be exposed from src/test/resources")
        assertTrue(resource.readText().contains("<mapper"))
    }
}

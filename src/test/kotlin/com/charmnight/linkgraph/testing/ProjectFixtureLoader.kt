package com.charmnight.linkgraph.testing

import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import java.nio.charset.StandardCharsets
import java.nio.file.Path

private const val PROJECT_FIXTURE_ROOT = "com/charmnight/linkgraph/fixtures"

fun CodeInsightTestFixture.addJavaFixture(
    relativePath: String,
    projectRelativePath: String = "$PROJECT_FIXTURE_ROOT/$relativePath",
) {
    addFileToProject(projectRelativePath, readJavaFixture(relativePath))
}

fun CodeInsightTestFixture.addKotlinFixture(
    relativePath: String,
    projectRelativePath: String = "$PROJECT_FIXTURE_ROOT/$relativePath",
) {
    addFileToProject(projectRelativePath, readKotlinFixture(relativePath))
}

fun CodeInsightTestFixture.addResourceFixture(
    relativePath: String,
    projectRelativePath: String = "$PROJECT_FIXTURE_ROOT/$relativePath",
) {
    addFileToProject(projectRelativePath, readResourceFixture(relativePath))
}

fun readJavaFixture(relativePath: String): String = readFixture("fixtures/java/$relativePath")

fun readKotlinFixture(relativePath: String): String = readFixture("fixtures/kotlin/$relativePath")

fun readResourceFixture(relativePath: String): String = readFixture("fixtures/resources/$relativePath")

fun fixtureFileName(relativePath: String): String = Path.of(relativePath).fileName.toString()

private fun readFixture(resourcePath: String): String {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
        ?: error("Fixture resource not found: $resourcePath")
    return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

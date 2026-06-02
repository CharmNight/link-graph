package com.charmnight.linkgraph.jvm.index

class JvmSymbolIndex(
    val modulesByName: Map<String, JvmModuleSymbol> = emptyMap(),
    val packagesByName: Map<String, JvmPackageSymbol> = emptyMap(),
    val classesByQualifiedName: Map<String, JvmClassSymbol> = emptyMap(),
    val methodsBySignature: Map<String, JvmMethodSymbol> = emptyMap(),
    val fieldsByQualifiedName: Map<String, JvmFieldSymbol> = emptyMap(),
    val resourcesByPath: Map<String, JvmResourceSymbol> = emptyMap(),
    val serviceProviderIndex: JvmServiceProviderIndex = JvmServiceProviderIndex(),
) {
    val symbolsById: Map<String, JvmSymbol> = buildMap {
        modulesByName.values.forEach { symbol -> put(symbol.id, symbol) }
        packagesByName.values.forEach { symbol -> put(symbol.id, symbol) }
        classesByQualifiedName.values.forEach { symbol -> put(symbol.id, symbol) }
        methodsBySignature.values.forEach { symbol -> put(symbol.id, symbol) }
        fieldsByQualifiedName.values.forEach { symbol -> put(symbol.id, symbol) }
        resourcesByPath.values.forEach { symbol -> put(symbol.id, symbol) }
    }

    fun findClass(qualifiedName: String): JvmClassSymbol? = classesByQualifiedName[qualifiedName]

    fun findClassByTypeName(
        typeName: String?,
        ownerPackageName: String? = null,
    ): JvmClassSymbol? {
        val normalized = normalizeTypeName(typeName) ?: return null
        classesByQualifiedName[normalized]?.let { return it }
        if (normalized.contains('.')) {
            return null
        }
        ownerPackageName
            ?.takeIf(String::isNotBlank)
            ?.let { packageName -> classesByQualifiedName["$packageName.$normalized"] }
            ?.let { return it }
        return classesByQualifiedName.values
            .filter { symbol -> symbol.simpleName == normalized }
            .singleOrNull()
    }

    fun findMethod(signature: String): JvmMethodSymbol? = methodsBySignature[signature]

    fun findField(qualifiedName: String): JvmFieldSymbol? = fieldsByQualifiedName[qualifiedName]

    fun findResource(path: String): JvmResourceSymbol? = resourcesByPath[path]

    fun findSymbol(symbolId: String): JvmSymbol? = symbolsById[symbolId]

    private fun normalizeTypeName(typeName: String?): String? {
        var normalized = typeName
            ?.trim()
            ?.substringBefore('<')
            ?.removeSuffix("?")
            ?.takeIf(String::isNotBlank)
            ?: return null
        while (normalized.endsWith("[]")) {
            normalized = normalized.removeSuffix("[]")
        }
        return normalized.takeIf(String::isNotBlank)
    }
}

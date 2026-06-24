package com.charmnight.linkgraph.jvm.index

/**
 * JVM 符号索引。
 *
 * 把项目内的所有符号按不同维度索引（模块/包/类/方法/字段/资源），
 * 让关系解析、跳转、搜索等模块可以快速查找。
 *
 * 索引在构建时是不可变的；重建时整体替换。
 */
class JvmSymbolIndex(
    /** 模块名 → 模块符号。 */
    val modulesByName: Map<String, JvmModuleSymbol> = emptyMap(),
    /** 包名 → 包符号。 */
    val packagesByName: Map<String, JvmPackageSymbol> = emptyMap(),
    /** 全限定名 → 类符号。 */
    val classesByQualifiedName: Map<String, JvmClassSymbol> = emptyMap(),
    /** 方法签名 → 方法符号。 */
    val methodsBySignature: Map<String, JvmMethodSymbol> = emptyMap(),
    /** 全限定名 → 字段符号。 */
    val fieldsByQualifiedName: Map<String, JvmFieldSymbol> = emptyMap(),
    /** 资源路径 → 资源符号。 */
    val resourcesByPath: Map<String, JvmResourceSymbol> = emptyMap(),
    /** 服务提供者索引（用于 SPI 解析）。 */
    val serviceProviderIndex: JvmServiceProviderIndex = JvmServiceProviderIndex(),
) {
    /**
     * 全部符号按 ID 索引的派生映射。
     * 把上述 6 张表合并为"id → symbol"，便于按 ID 查询任意符号。
     */
    val symbolsById: Map<String, JvmSymbol> = buildMap {
        modulesByName.values.forEach { symbol -> put(symbol.id, symbol) }
        packagesByName.values.forEach { symbol -> put(symbol.id, symbol) }
        classesByQualifiedName.values.forEach { symbol -> put(symbol.id, symbol) }
        methodsBySignature.values.forEach { symbol -> put(symbol.id, symbol) }
        fieldsByQualifiedName.values.forEach { symbol -> put(symbol.id, symbol) }
        resourcesByPath.values.forEach { symbol -> put(symbol.id, symbol) }
    }

    /** 按全限定名查类。 */
    fun findClass(qualifiedName: String): JvmClassSymbol? = classesByQualifiedName[qualifiedName]

    /**
     * 按类型名查类。
     *
     * 支持泛型擦除（去掉 `<...>`）、可空标记（去 `?`）、数组（去 `[]`）。
     * 简单名匹配时若结果不唯一会返回 null（避免误命中）。
     *
     * @param typeName 类型名字符串
     * @param ownerPackageName 所属包名；用于简单名匹配时补全
     */
    fun findClassByTypeName(
        typeName: String?,
        ownerPackageName: String? = null,
    ): JvmClassSymbol? {
        val normalized = normalizeTypeName(typeName) ?: return null
        // 直接按全限定名命中
        classesByQualifiedName[normalized]?.let { return it }
        // 已含包分隔的名称不再尝试补全
        if (normalized.contains('.')) {
            return null
        }
        // 用 ownerPackageName 补全后再查
        ownerPackageName
            ?.takeIf(String::isNotBlank)
            ?.let { packageName -> classesByQualifiedName["$packageName.$normalized"] }
            ?.let { return it }
        // 兜底：按简单名匹配，且要求结果唯一
        return classesByQualifiedName.values
            .filter { symbol -> symbol.simpleName == normalized }
            .singleOrNull()
    }

    /** 按签名查方法。 */
    fun findMethod(signature: String): JvmMethodSymbol? = methodsBySignature[signature]

    /** 按全限定名查字段。 */
    fun findField(qualifiedName: String): JvmFieldSymbol? = fieldsByQualifiedName[qualifiedName]

    /** 按路径查资源。 */
    fun findResource(path: String): JvmResourceSymbol? = resourcesByPath[path]

    /** 按 ID 查任意符号。 */
    fun findSymbol(symbolId: String): JvmSymbol? = symbolsById[symbolId]

    /**
     * 规范化类型名：去掉前后空白、泛型参数、可空标记、数组后缀。
     * 用于让 "List<String>"、"String[]"、"String?" 等都能匹配到对应的类。
     */
    private fun normalizeTypeName(typeName: String?): String? {
        var normalized = typeName
            ?.trim()
            ?.substringBefore('<')
            ?.removeSuffix("?")
            ?.takeIf(String::isNotBlank)
            ?: return null
        // 循环去掉数组后缀，支持多维数组
        while (normalized.endsWith("[]")) {
            normalized = normalized.removeSuffix("[]")
        }
        return normalized.takeIf(String::isNotBlank)
    }
}

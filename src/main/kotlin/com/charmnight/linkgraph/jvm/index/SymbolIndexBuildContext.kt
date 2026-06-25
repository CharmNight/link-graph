package com.charmnight.linkgraph.jvm.index

import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 符号索引构建过程中所有 indexer 共享的可变状态容器（P2-1 深度重构）。
 *
 * 之前 JvmSymbolIndexBuilder 的每个 index 方法都接收 6-7 个 MutableMap 作为参数，
 * 方法签名冗长且容易传错。本 class 把这些 Map + budget + project + fileFilter 统一包装，
 * 让 indexer 只需接收一个 context 参数。
 *
 * 生命周期：由 [JvmSymbolIndexBuilder.build] 创建，贯穿整个索引构建流程，
 * 所有 indexer class 往同一个 context 写入结果。构建结束后 context 被丢弃。
 *
 * @param project 当前 IntelliJ 项目
 * @param budget 索引预算（各类上限）
 * @param fileFilter 文件过滤回调
 * @param modules module 符号表（key = module name）
 * @param packages package 符号表（key = qualified name）
 * @param classes class 符号表（key = qualified name）
 * @param methods method 符号表（key = signature）
 * @param fields field 符号表（key = qualified name）
 * @param resources resource 符号表（key = path）
 * @param serviceFiles SPI 服务文件表（key = interface name → provider files）
 */
internal class SymbolIndexBuildContext(
    val project: Project,
    val budget: JvmResolutionBudget,
    val fileFilter: (VirtualFile) -> Boolean,
    val modules: MutableMap<String, JvmModuleSymbol> = linkedMapOf(),
    val packages: MutableMap<String, JvmPackageSymbol> = linkedMapOf(),
    val classes: MutableMap<String, JvmClassSymbol> = linkedMapOf(),
    val methods: MutableMap<String, JvmMethodSymbol> = linkedMapOf(),
    val fields: MutableMap<String, JvmFieldSymbol> = linkedMapOf(),
    val resources: MutableMap<String, JvmResourceSymbol> = linkedMapOf(),
    val serviceFiles: MutableMap<String, MutableList<JvmServiceProviderFile>> = linkedMapOf(),
) {
    /** 检查是否已达到项目类上限。 */
    fun isFull(): Boolean = classes.size >= budget.maxProjectClasses

    /** 检查是否已达到方法上限。 */
    fun isMethodsFull(): Boolean = methods.size >= budget.maxMethods

    /** 把所有符号组装为不可变 [JvmSymbolIndex] 返回。 */
    fun toSymbolIndex(): JvmSymbolIndex = JvmSymbolIndex(
        modulesByName = modules.toMap(),
        packagesByName = packages.toMap(),
        classesByQualifiedName = classes.toMap(),
        methodsBySignature = methods.toMap(),
        fieldsByQualifiedName = fields.toMap(),
        resourcesByPath = resources.toMap(),
        serviceProviderIndex = JvmServiceProviderIndex(
            filesByInterfaceName = serviceFiles.mapValues { it.value.toList() },
        ),
    )
}

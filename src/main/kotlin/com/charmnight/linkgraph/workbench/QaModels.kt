package com.charmnight.linkgraph.workbench

/**
 * QA（助理问答）的工作模式。
 *
 * - [AUTO]：自动判定模式，由系统根据上下文选择最合适的问答策略；
 * - [ANSWER]：纯回答模式，只给解释不做改动；
 * - [REVIEW]：审查模式，聚焦差异项的代码审查；
 * - [CHANGE]：变更模式，主动产出可应用的改动；
 * - [INVESTIGATE]：调查模式，针对风险点展开深入取证。
 *
 * 不同模式会触发不同的提示词与工具集合。
 */
enum class QaMode {
    AUTO,
    ANSWER,
    REVIEW,
    CHANGE,
    INVESTIGATE,
}

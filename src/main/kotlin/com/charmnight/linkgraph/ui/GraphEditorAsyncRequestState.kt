package com.charmnight.linkgraph.ui

// UI 层为了减少跨模块 import 的样板代码，把应用层定义的异步请求相关类型在这里做一次别名透出。
// 这些 typealias 不引入新语义，只是让 UI 层代码可以直接通过 com.charmnight.linkgraph.ui.AsyncRequestState 等短名访问。

/** 异步请求执行模式的别名，透出 [com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode]。 */
typealias AsyncRequestExecutionMode = com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode

/** 异步请求相位的别名，透出 [com.charmnight.linkgraph.application.model.AsyncRequestPhase]。 */
typealias AsyncRequestPhase = com.charmnight.linkgraph.application.model.AsyncRequestPhase

/** 异步请求整体状态的别名，透出 [com.charmnight.linkgraph.application.model.AsyncRequestState]。 */
typealias AsyncRequestState = com.charmnight.linkgraph.application.model.AsyncRequestState

# 继续取证 Resolver 组织规则

`resolving` 目录保存风险线程继续取证使用的证据解析器。解析器按语义边界拆分子包，而不是按调用顺序或实现复杂度拆分。

- `java/`
  - 放置通用 Java PSI、Java 符号、反射、SPI、枚举常量和覆写关系相关 resolver。
  - 这些 resolver 只依赖 Java 语言、JVM 符号或 IntelliJ Java PSI 能力，不表达具体业务框架语义。
- `spring/`
  - 放置 Spring 语义相关 resolver。
  - 这些 resolver 可以解释 Spring 事件、注解、Bean 或框架约定，因此不和通用 Java PSI resolver 混放。

新增 resolver 时，先判断证据语义是否属于语言/JVM 通用能力；如果是，放入 `java/`。如果需要解释 Spring 语义或 Spring 框架约定，放入 `spring/`。

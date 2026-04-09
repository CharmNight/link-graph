# 快速开始

## 目标读者

这份文档面向第一次在本地运行 Link Graph 的开发者。它只关注最短可运行路径，不展开解释内部实现细节。

## 环境要求

- JDK 17
- Node.js 与 npm
- 与仓库目标平台兼容的 IntelliJ IDEA

## 第一次运行

1. 安装前端依赖

```bash
npm --prefix web ci
```

2. 运行后端测试，确认基础环境可用

```bash
./gradlew test
```

3. 启动插件沙箱 IDE

```bash
./gradlew runIde
```

4. 在沙箱 IDE 中打开一个包含 Java 或 Kotlin 源码的项目

5. 通过以下任一入口打开 Link Graph

- 编辑器右键菜单中的链路图动作
- 工具菜单中的链路图分组
- 右侧工具窗口中的链路图面板

## 最小验证步骤

建议至少完成以下验证：

1. 在一个方法上打开事实图
2. 在三种视图之间切换
3. 尝试导入或导出 Mermaid
4. 点击节点执行源码跳转
5. 打开设置页，确认可以看到 LLM 相关配置入口

## 常见问题

### 前端资源没有加载

优先确认：

- `npm --prefix web ci` 已执行
- `./gradlew runIde` 启动前没有前端构建失败
- `web/dist` 或打包后的 `linkgraph/` 资源存在

### 插件动作没有出现

优先确认：

- 当前打开的是支持的项目类型
- 沙箱 IDE 已成功加载当前插件
- 插件没有因为启动异常而被禁用

## 下一步

- 开发者请继续阅读 [development.md](development.md)
- 想理解实现结构请阅读 [architecture.md](architecture.md)
- 想确认当前支持范围请阅读 [features-and-limitations.md](features-and-limitations.md)

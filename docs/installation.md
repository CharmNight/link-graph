# 安装

Link Graph 是一个 IntelliJ Platform 插件,当前提供两种安装方式。

## 方式一:从源码构建(推荐,当前唯一稳定来源)

插件尚未发布到 JetBrains Marketplace,请暂时从源码构建并安装到沙箱 IDE 或当前 IDEA 中。

### 环境要求

- JDK 17
- Node.js `^20.19.0` 或 `>=22.12.0`
- IntelliJ IDEA 2023.3.4 或更高兼容版本

### 构建并安装

```bash
# 1. 克隆仓库
git clone https://github.com/CharmNight/link-graph.git
cd link-graph

# 2. 安装前端依赖
npm --prefix web ci

# 3. 构建插件分发 zip
./gradlew buildPlugin
```

构建产物位于 `build/distributions/Link Graph-<version>.zip`。

### 在 IDEA 中安装

- 通过磁盘安装
  1. 打开 `Settings/Preferences -> Plugins`。
  2. 点击右上角齿轮图标,选择 `Install Plugin from Disk...`。
  3. 选择上面构建出的 zip 文件。
  4. 重启 IDE。

- 或在沙箱 IDE 中验证
  ```bash
  ./gradlew runIde
  ```
  `runIde` 会启动一个带插件的独立 IDE 实例,适合调试和功能验证。

## 方式二:JetBrains Marketplace(准备中)

插件正在准备上架 JetBrains Marketplace。上架后,可以通过:

- IDE 内 `Settings/Preferences -> Plugins -> Marketplace`,搜索 `Link Graph` 或插件 ID `com.charmnight.linkgraph`。
- 或访问插件详情页(链接将在上架后更新)。

直接安装并重启 IDE。

## 启用与验证

1. 打开任意 Java 或 Kotlin 项目。
2. 通过以下任一入口打开 Link Graph:
   - 编辑器右键菜单的链路图动作
   - `Tools -> 链路图` 菜单
   - 右侧工具窗口中的链路图面板
3. 第一次使用时,建议从事实图开始。具体操作见 [使用说明](usage.md)。

## 更新

- 源码安装:拉取最新代码,重新执行 `./gradlew buildPlugin`,在 IDE 中替换插件即可。
- Marketplace 安装:IDE 会自动提示新版本,点击更新即可。

## 卸载

通过 `Settings/Preferences -> Plugins -> Installed`,找到 Link Graph 并点击 `Disable` 或 `Uninstall`。

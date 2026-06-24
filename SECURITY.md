# 安全策略

## 支持版本

Link Graph 仍处于早期演进阶段,仅对最新版本(当前为 `0.1.2`)提供安全维护。旧版本的安全修复不会单独 backport,建议始终使用最新版本。

版本号见 [CHANGELOG.md](CHANGELOG.md)。

## 报告方式

涉及安全的问题**请不要直接提交公开 GitHub Issue**。

首选渠道(强烈推荐):

- **GitHub Security Advisory**
  - 打开仓库的 `Security` 标签页,点击 `Report a vulnerability`,或直接访问:
    `https://github.com/CharmNight/link-graph/security/advisories/new`
  - 这条渠道支持私密协作,并能申请 CVE 编号。

备用渠道(若 GitHub Advisory 不可用):

- 通过 GitHub 个人资料中提供的邮箱私下联系维护者。
- 邮件标题请以 `[Link Graph Security]` 开头,便于识别。

请在报告中尽量附带以下信息:

- 问题的清晰描述
- 受影响的版本或提交范围
- 复现步骤或最小 PoC
- 已知影响范围
- 你建议的修复方向(可选)

## 重点关注范围

以下类型的问题尤其值得报告:

- 远程 LLM 请求处理(凭据拼接、模型回包解析、网络层)
- 凭据存储或凭据意外泄露(包括写入日志、写入 Artifact、下发到前端)
- 文件写入相关流程(代码 diff 写回、草稿写回、临时文件残留)
- 源码跳转与项目路径处理(跨项目读取、目录穿越)
- 内嵌前端资源加载与前后端桥接消息(命令注入、脚本注入)

## 不在范围

- 已经在 [features-and-limitations.md](docs/features-and-limitations.md) 中明确说明的已知限制
- 本地规则回退路径导致的"结果不像远程模型"的体验问题(这是设计行为,不是安全缺陷)
- 对第三方 LLM 服务自身安全的报告(请直接报告给对应供应商)

## 响应预期

本项目按维护者可用时间尽力维护。收到报告后会尽快确认、分级和修复:

- **首次响应**:尽力在 5 个工作日内回复确认收到。
- **修复时间**:根据问题严重程度和可复现性评估,通常在 30 天内给出修复方案或缓解措施。
- **协调披露**:如果你通过 GitHub Security Advisory 报告,可以在 advisory 中与维护者协调披露时间。

修复发布后,我们会在 [CHANGELOG.md](CHANGELOG.md) 中记录安全问题修复,并致谢报告者(除非报告者要求匿名)。

import type { ClassUsageGroup, ClassUsageKind, ClassUsageOwnerKind, ClassUsageSearchResult } from "../../types";

/**
 * 类用法面板：在类图侧栏展示某个类被哪些位置引用，按"分组 → 单条用法"两层结构呈现。
 * 顶部展示目标类名和命中数量统计，并对截断/包含 import 等特殊状态做提示标注，
 * 让用户对搜索结果的完整度有直观认知。
 */
export function ClassUsagePanel({ usage }: { usage: ClassUsageSearchResult }) {
  // 是否存在至少一个使用分组，用于切换列表与空状态两种渲染分支
  const hasGroups = usage.groups.length > 0;
  return (
    <section className="class-usage-panel" aria-label="类使用处">
      <header className="class-usage-panel-head">
        <div className="class-usage-title-block">
          <span className="canvas-reading-label">使用处</span>
          <strong title={usage.target.qualifiedName}>{usage.target.displayName} 使用处</strong>
          <span title={usage.target.qualifiedName}>{usage.target.qualifiedName}</span>
        </div>
        <div className="class-usage-summary-row">
          <span>{usageSummaryText(usage)}</span>
          {usage.summary.truncated ? (
            <span className="canvas-reading-flag is-warning">
              {usage.summary.canRequestMore ? "结果已截断" : "已达上限"}
            </span>
          ) : null}
          {usage.summary.includeImports ? <span className="canvas-reading-flag is-info">包含 import</span> : null}
        </div>
      </header>
      {hasGroups ? (
        <div className="class-usage-group-list">
          {usage.groups.map((group) => (
            <ClassUsageGroupView key={group.id} group={group} />
          ))}
        </div>
      ) : (
        <div className="class-usage-empty">
          <strong>未找到使用处</strong>
        </div>
      )}
    </section>
  );
}

/**
 * 单个使用分组的渲染组件：一个分组通常对应一个类/方法/文件，
 * 内部再以列表形式罗列该上下文中的所有具体引用位置（行号、文本、所属方法签名）。
 */
function ClassUsageGroupView({ group }: { group: ClassUsageGroup }) {
  return (
    <article className="class-usage-group">
      <header className="class-usage-group-head">
        <div>
          <strong title={group.qualifiedName ?? group.filePath ?? group.title}>{group.title}</strong>
          {group.qualifiedName ? <span title={group.qualifiedName}>{group.qualifiedName}</span> : null}
        </div>
        <span>{ownerKindLabel(group.ownerKind)}</span>
      </header>
      {group.filePath ? <code className="class-usage-path" title={group.filePath}>{group.filePath}</code> : null}
      <ol className="class-usage-entry-list">
        {group.usages.map((entry) => (
          <li className="class-usage-entry" key={entry.id}>
            <div className="class-usage-entry-meta">
              <span className="class-usage-kind">{usageKindLabel(entry.kind)}</span>
              <code>{entry.line}:{entry.column}</code>
              {entry.ownerMethodSignature ? <span title={entry.ownerMethodSignature}>{entry.ownerMethodSignature}</span> : null}
            </div>
            <code className="class-usage-entry-text" title={entry.text}>{entry.text}</code>
          </li>
        ))}
      </ol>
    </article>
  );
}

/**
 * 把搜索结果的数量统计格式化为人类可读的摘要字符串，
 * 形如"3 / 10 分组 · 25 / 100 处"，左侧是当前可见数、右侧是总数，便于一眼看出截断比例。
 */
function usageSummaryText(usage: ClassUsageSearchResult): string {
  return [
    `${usage.summary.visibleGroupCount} / ${usage.summary.groupCount} 分组`,
    `${usage.summary.visibleUsageCount} / ${usage.summary.usageCount} 处`,
  ].join(" · ");
}

/**
 * 将使用分组的拥有者类型（类/方法/文件）映射为中文标签，
 * 用于在分组标题旁显示该用法的归属层级。
 */
function ownerKindLabel(kind: ClassUsageOwnerKind): string {
  switch (kind) {
    case "CLASS":
      return "类";
    case "METHOD":
      return "方法";
    case "FILE":
      return "文件";
    default:
      return kind;
  }
}

/**
 * 将单条用法的语义类型（类型引用、字段、参数、返回值、构造调用、注解等）
 * 映射为中文标签，让用户在不用阅读源码的情况下就能判断每条引用的角色。
 * 对外导出，便于其他模块（如表格视图）复用同一套术语。
 */
export function usageKindLabel(kind: ClassUsageKind): string {
  switch (kind) {
    case "TYPE_REFERENCE":
      return "类型引用";
    case "FIELD_TYPE":
      return "字段";
    case "METHOD_PARAMETER":
      return "参数";
    case "METHOD_RETURN":
      return "返回";
    case "CONSTRUCTOR_CALL":
      return "构造";
    case "ANNOTATION":
      return "注解";
    case "IMPORT":
      return "导入";
    case "EXTENDS":
      return "继承";
    case "IMPLEMENTS":
      return "实现";
    case "OTHER":
      return "其他";
    default:
      return kind;
  }
}

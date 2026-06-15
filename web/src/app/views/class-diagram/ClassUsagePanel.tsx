import type { ClassUsageGroup, ClassUsageKind, ClassUsageOwnerKind, ClassUsageSearchResult } from "../../types";

export function ClassUsagePanel({ usage }: { usage: ClassUsageSearchResult }) {
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

function usageSummaryText(usage: ClassUsageSearchResult): string {
  return [
    `${usage.summary.visibleGroupCount} / ${usage.summary.groupCount} 分组`,
    `${usage.summary.visibleUsageCount} / ${usage.summary.usageCount} 处`,
  ].join(" · ");
}

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

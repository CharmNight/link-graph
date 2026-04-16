import type { CSSProperties, ReactNode } from "react";

interface WorkbenchSectionProps {
  sectionId?: string;
  title: string;
  expanded: boolean;
  onToggle: (nextExpanded: boolean) => void;
  children?: ReactNode;
  className?: string;
  bodyClassName?: string;
  eyebrow?: string | null;
  meta?: ReactNode;
  actions?: ReactNode;
  minBodyHeight?: number | null;
}

export function WorkbenchSection({
  sectionId,
  title,
  expanded,
  onToggle,
  children = null,
  className = "",
  bodyClassName = "",
  eyebrow = null,
  meta = null,
  actions = null,
  minBodyHeight = null,
}: WorkbenchSectionProps) {
  const classes = ["workbench-section-card mb12px", expanded ? "expanded" : "collapsed", className].filter(Boolean).join(" ");
  const bodyClasses = ["workbench-section-card-body", bodyClassName].filter(Boolean).join(" ");
  const bodyStyle: CSSProperties | undefined = minBodyHeight != null ? { minHeight: `${minBodyHeight}px` } : undefined;

  return (
    <section className={classes} data-section-id={sectionId}>
      <div className="workbench-section-card-head">
        {expanded ? (
          <div className="workbench-section-card-title">
            {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
            <h3>{title}</h3>
          </div>
        ) : (
          <button
            type="button"
            className="workbench-section-card-trigger"
            aria-expanded={false}
            aria-label={title}
            onClick={() => onToggle(true)}
          >
            <span className="workbench-section-card-title">
              {eyebrow ? <span className="eyebrow">{eyebrow}</span> : null}
              <span className="workbench-section-card-trigger-title">{title}</span>
            </span>
          </button>
        )}
        <div className="workbench-section-card-controls">
          {meta}
          {actions}
          {expanded ? (
            <button
              type="button"
              className="ghost-button compact"
              aria-label={`收起${title}`}
              aria-expanded
              onClick={() => onToggle(false)}
            >
              收起
            </button>
          ) : null}
        </div>
      </div>
      {expanded ? (
        <div className={bodyClasses} style={bodyStyle}>
          {children}
        </div>
      ) : null}
    </section>
  );
}

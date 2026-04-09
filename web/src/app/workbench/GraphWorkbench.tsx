import type { ReactNode } from "react";

interface GraphWorkbenchProps {
  toolbar: ReactNode;
  legend?: ReactNode;
  summary?: ReactNode;
  dialogs?: ReactNode;
  stage: ReactNode;
  propertyDrawer?: ReactNode;
  dock?: ReactNode;
}

export function GraphWorkbench({
  toolbar,
  legend = null,
  summary = null,
  dialogs = null,
  stage,
  propertyDrawer = null,
  dock = null,
}: GraphWorkbenchProps) {
  return (
    <div className="app-shell graph-workbench" data-testid="graph-workbench">
      {toolbar}
      {legend}
      {summary}
      {dialogs}

      <main className="workspace-stage">
        <div className="workspace-stage-content">
          {stage}
        </div>
      </main>

      {propertyDrawer}
      {dock}
    </div>
  );
}

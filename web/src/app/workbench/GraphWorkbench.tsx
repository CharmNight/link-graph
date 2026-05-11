import type { ReactNode } from "react";

interface GraphWorkbenchProps {
  taskbar: ReactNode;
  body: ReactNode;
  tray?: ReactNode;
  dialogs?: ReactNode;
  propertyDrawer?: ReactNode;
}

export function GraphWorkbench({
  taskbar,
  body,
  tray = null,
  dialogs = null,
  propertyDrawer = null,
}: GraphWorkbenchProps) {
  return (
    <div className="app-shell graph-workbench" data-testid="graph-workbench">
      {taskbar}
      {dialogs}
      <main className="hybrid-workbench-body-slot">
        {body}
      </main>
      {tray}
      {propertyDrawer}
    </div>
  );
}

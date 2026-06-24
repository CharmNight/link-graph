import type { ReactNode } from "react";

/** GraphWorkbench 组件的入参。所有 slot 都是 ReactNode，由父组件填充。 */
interface GraphWorkbenchProps {
  /** 顶部任务栏 slot。 */
  taskbar: ReactNode;
  /** 主体内容 slot（图谱 + 助理 + 大纲）。 */
  body: ReactNode;
  /** 底部变更托盘 slot；可空。 */
  tray?: ReactNode;
  /** 弹窗 slot；可空。 */
  dialogs?: ReactNode;
  /** 属性抽屉 slot；可空。 */
  propertyDrawer?: ReactNode;
}

/**
 * 图谱工作台的外壳布局。
 *
 * 把任务栏、主体、托盘、弹窗、属性抽屉按固定结构组合到 DOM 中。
 * 本组件不持有任何状态，只负责布局骨架——具体内容由各 slot 传入。
 */
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

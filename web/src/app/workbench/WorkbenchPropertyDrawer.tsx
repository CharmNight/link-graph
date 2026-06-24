import type { ComponentProps } from "react";
import { PropertyPanel } from "../components/PropertyPanel";

/**
 * 工作台属性抽屉。
 *
 * 这是对 [PropertyPanel] 的薄封装，让工作台层在概念上有一个"专属的属性抽屉"，
 * 但实际渲染复用通用组件。未来如果工作台需要专属交互（例如额外的折叠/锁定按钮），
 * 可以在本组件中扩展，而不需要污染通用 PropertyPanel。
 */
export function WorkbenchPropertyDrawer(props: ComponentProps<typeof PropertyPanel>) {
  return <PropertyPanel {...props} />;
}

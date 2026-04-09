import type { ComponentProps } from "react";
import { PropertyPanel } from "../components/PropertyPanel";

export function WorkbenchPropertyDrawer(props: ComponentProps<typeof PropertyPanel>) {
  return <PropertyPanel {...props} />;
}

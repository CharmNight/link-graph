import type { ComponentProps } from "react";
import { Toolbar } from "../components/Toolbar";

export function WorkbenchToolbar(props: ComponentProps<typeof Toolbar>) {
  return <Toolbar {...props} />;
}

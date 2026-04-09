import type { ComponentProps } from "react";
import { SelectedNodeSummary } from "../components/SelectedNodeSummary";

export function WorkbenchSummary(props: ComponentProps<typeof SelectedNodeSummary>) {
  return <SelectedNodeSummary {...props} />;
}

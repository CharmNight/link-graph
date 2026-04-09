import type { ComponentProps } from "react";
import { ResultPanelOverlay } from "../components/ResultPanelOverlay";

interface WorkbenchDockProps extends ComponentProps<typeof ResultPanelOverlay> {
  open: boolean;
}

export function WorkbenchDock({
  open,
  children,
  ...overlayProps
}: WorkbenchDockProps) {
  if (!open) {
    return null;
  }
  return (
    <ResultPanelOverlay {...overlayProps}>
      {children}
    </ResultPanelOverlay>
  );
}

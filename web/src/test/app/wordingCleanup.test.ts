import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const APP_ROOT = path.resolve(import.meta.dirname, "../../app");

describe("frontend wording cleanup", () => {
  it("does not keep 审计 wording in production app files", () => {
    const files = [
      "App.tsx",
      "asyncRequestStatus.ts",
      "labels.ts",
      "components/Toolbar.tsx",
      "components/graph/actions/actionSchema.ts",
      "views/flowchart/FlowchartView.tsx",
      "views/resource/ResourceRelationView.tsx",
      "workbench/AuditConversation.tsx",
      "workbench/AuditTab.tsx",
      "workbench/DraftDetailPanel.tsx",
      "workbench/DraftTab.tsx",
      "workbench/InvestigationLeadList.tsx",
    ];

    for (const relativePath of files) {
      const content = readFileSync(path.join(APP_ROOT, relativePath), "utf8");
      expect(content, `前端生产文件不应继续保留“审计”口径：${relativePath}`).not.toContain("审计");
    }
  });
});

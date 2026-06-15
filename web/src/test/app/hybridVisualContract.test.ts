import { describe, expect, it } from "vitest";
import themeCss from "../../app/theme.css?raw";

describe("hybrid visual contract", () => {
  it("uses IntelliJ-like theme tokens with a light color-scheme override instead of a forced black shell", () => {
    expect(themeCss).toMatch(/color-scheme:\s*dark\s+light;/);
    expect(themeCss).toMatch(/--bg:\s*#1e1f22;/);
    expect(themeCss).toMatch(/--panel:\s*#2b2d30;/);
    expect(themeCss).not.toMatch(/--bg:\s*#080c12;/);
    expect(themeCss).toMatch(
      /@media\s*\(prefers-color-scheme:\s*light\)\s*\{[\s\S]*?:root\s*\{[\s\S]*?--bg:\s*#f4f4f5;[\s\S]*?--text:\s*#1f2328;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /:root\[data-idea-theme="dark"\]\s*\{[\s\S]*?color-scheme:\s*dark;[\s\S]*?--bg:\s*#1e1f22;[\s\S]*?--text:\s*#f4f4f5;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /:root\[data-idea-theme="light"\]\s*\{[\s\S]*?color-scheme:\s*light;[\s\S]*?--bg:\s*#f4f4f5;[\s\S]*?--text:\s*#1f2328;[\s\S]*?\}/s,
    );
  });

  it("keeps assistant workbench cards readable in the final right rail", () => {
    const removedStageShellClass = ["stage", "workbench"].join("-");
    expect(themeCss).not.toContain(`.${removedStageShellClass}`);
    expect(themeCss).toMatch(
      /\.assistant-workbench-shell\s*\{[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto;[^}]*overflow:\s*hidden;/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-thread\s*\{[^}]*overflow-y:\s*auto;[^}]*overflow-x:\s*hidden;[^}]*overscroll-behavior:\s*contain;/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-turn-card\s*\{(?=[^}]*background:\s*var\(--panel-soft\);)(?=[^}]*color:\s*var\(--text\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-evidence-item\s*\{(?=[^}]*background:\s*var\(--panel\);)(?=[^}]*border:\s*1px\s+solid\s+var\(--line-soft\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-question\s*\{[^}]*background:\s*var\(--canvas\);[^}]*color:\s*var\(--text\);/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-composer\s*\{[^}]*border-top:\s*1px\s+solid\s+var\(--line\);[^}]*background:\s*var\(--panel-soft\);/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-composer\s+\.assistant-send-type-selector\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\);/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-composer-sticky\s*\{[^}]*position:\s*sticky;[^}]*bottom:\s*0;/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-composer\s+textarea\s*\{(?=[^}]*min-height:\s*96px;)(?=[^}]*max-height:\s*132px;)(?=[^}]*resize:\s*none;)[^}]*\}/s,
    );
  });

  it("uses dark tokenized preview surfaces inside the assistant right rail", () => {
    expect(themeCss).toMatch(
      /\.assistant-workbench-shell\s+:is\(\.prompt-preview,\s*\.request-state-preview,\s*pre,\s*code\)\s*\{(?=[^}]*background:\s*var\(--code-bg\);)(?=[^}]*color:\s*var\(--code-text\);)(?=[^}]*font-family:\s*var\(--font-mono\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-workbench-shell\s+\.prompt-preview\s*\{(?=[^}]*max-height:\s*min\(42vh,\s*360px\);)(?=[^}]*overflow:\s*auto;)[^}]*\}/s,
    );
    expect(themeCss).not.toMatch(
      /\.assistant-workbench-shell[\s\S]*?\.prompt-preview\s*\{[^}]*background:\s*rgba\(255,\s*255,\s*255/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-failure-notice\s*\{(?=[^}]*background:\s*var\(--danger-bg\);)(?=[^}]*color:\s*var\(--text\);)[^}]*\}/s,
    );
    expect(themeCss).not.toMatch(/\.assistant-failure-notice\s*\{[^}]*background:\s*rgba\(255,\s*244,\s*241/s);
  });

  it("centralizes form and code surface colors in theme tokens", () => {
    expect(themeCss).toMatch(/--control-bg:\s*#202327;/);
    expect(themeCss).toMatch(/--code-bg:\s*#181b20;/);
    expect(themeCss).toMatch(/--code-text:\s*#f4f4f5;/);
    expect(themeCss).toMatch(
      /input,\s*textarea,\s*select\s*\{(?=[^}]*background:\s*var\(--control-bg\);)(?=[^}]*color:\s*var\(--text\);)(?=[^}]*border:\s*1px\s+solid\s+var\(--line\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /input::placeholder,\s*textarea::placeholder\s*\{[^}]*color:\s*var\(--dim\);[^}]*\}/s,
    );
    expect(themeCss).not.toMatch(/input,\s*textarea,\s*select\s*\{[^}]*background:\s*rgba\(255,\s*255,\s*255/s);
  });

  it("keeps the top assistant status as a read-only summary instead of button-like chips", () => {
    expect(themeCss).toMatch(
      /\.workflow-taskbar\s*\{[^}]*grid-template-columns:\s*minmax\(230px,\s*0\.72fr\)\s+minmax\(360px,\s*1fr\)\s+auto;[^}]*background:\s*var\(--shell\);/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-status-strip\s*\{(?=[^}]*display:\s*flex;)(?=[^}]*flex-wrap:\s*wrap;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-status-heading\s*\{(?=[^}]*color:\s*var\(--muted\);)(?=[^}]*text-transform:\s*uppercase;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-status-item\s*\{(?=[^}]*display:\s*inline-flex;)(?=[^}]*background:\s*transparent;)(?=[^}]*border:\s*0;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-status-dot\s*\{(?=[^}]*border-radius:\s*999px;)(?=[^}]*background:\s*var\(--muted\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-status-label,\s*\.assistant-status-value\s*\{(?=[^}]*white-space:\s*normal;)(?=[^}]*overflow-wrap:\s*anywhere;)(?=[^}]*word-break:\s*break-word;)[^}]*\}/s,
    );
    expect(themeCss).not.toContain(".assistant-status-chip");
    expect(themeCss).not.toContain("workflow-overview");
    expect(themeCss).not.toContain(".workflow-stage-strip");
    expect(themeCss).not.toContain(".workflow-stage-button");
    expect(themeCss).not.toContain('aria-current="step"');
    expect(themeCss).not.toContain("repeat(5");
  });

  it("wraps assistant result content and evidence references instead of truncating them", () => {
    expect(themeCss).toMatch(
      /\.assistant-context-meta\s+\.toolbar-chip\s*\{(?=[^}]*white-space:\s*normal;)(?=[^}]*overflow-wrap:\s*anywhere;)(?=[^}]*word-break:\s*break-word;)[^}]*\}/s,
    );
    expect(themeCss).not.toMatch(/\.assistant-context-meta\s+\.toolbar-chip\s*\{[^}]*text-overflow:\s*ellipsis;/s);
    expect(themeCss).toMatch(
      /\.assistant-result-text,\s*\.assistant-evidence-item\s*>\s*:is\(strong,\s*p,\s*\.muted\),\s*\.assistant-turn-frame-head\s+\.muted,\s*\.assistant-turn-head\s+\.muted\s*\{(?=[^}]*white-space:\s*normal;)(?=[^}]*overflow-wrap:\s*anywhere;)(?=[^}]*word-break:\s*break-word;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-evidence-item\s+\.ghost-button,\s*\.assistant-card-flow\s+\.ghost-button\.compact\s*\{(?=[^}]*max-width:\s*100%;)(?=[^}]*height:\s*auto;)(?=[^}]*white-space:\s*normal;)(?=[^}]*overflow-wrap:\s*anywhere;)(?=[^}]*word-break:\s*break-word;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-evidence-item\s+\.panel-actions\s*\{(?=[^}]*min-width:\s*0;)(?=[^}]*align-items:\s*stretch;)[^}]*\}/s,
    );
    expect(themeCss).not.toMatch(/\.assistant-evidence-item\s+\.ghost-button[^{]*\{[^}]*white-space:\s*nowrap;/s);
  });

  it("keeps graph footer chrome compact so the canvas keeps vertical space", () => {
    expect(themeCss).toMatch(
      /\.graph-stage-footer\s*\{(?=[^}]*flex-wrap:\s*nowrap;)(?=[^}]*overflow-x:\s*auto;)(?=[^}]*min-height:\s*42px;)(?=[^}]*max-height:\s*42px;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-footer\s+:is\(\.status-pill,\s*\.app-pill\)\s*\{(?=[^}]*flex:\s*0\s+0\s+auto;)(?=[^}]*white-space:\s*nowrap;)[^}]*\}/s,
    );
  });

  it("scopes workbench headings instead of leaking global h2 styles", () => {
    expect(themeCss).not.toContain(".link-outline-head h2, h2,");
  });

  it("uses dark workbench control surfaces instead of light gray button chrome", () => {
    expect(themeCss).toMatch(
      /\.toolbar-chip\s*\{(?=[^}]*background:\s*var\(--canvas\);)(?=[^}]*color:\s*var\(--text\);)(?=[^}]*border:\s*1px\s+solid\s+var\(--line\);)[^}]*\}/s,
    );
    expect(themeCss).not.toMatch(/\.toolbar-chip\s*\{[^}]*rgba\(255,\s*255,\s*255/s);
    expect(themeCss).toMatch(
      /\.ghost-button\s*\{(?=[^}]*background:\s*var\(--canvas\);)(?=[^}]*color:\s*var\(--text\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.view-switch\s*\{(?=[^}]*background:\s*var\(--canvas\);)(?=[^}]*border:\s*1px\s+solid\s+var\(--line\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.view-switch\s+button\[aria-pressed="true"\]\s*\{(?=[^}]*background:\s*rgba\(56,\s*189,\s*248,\s*0\.13\);)(?=[^}]*color:\s*var\(--text\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.app-pill,\s*\.risk-pill,\s*\.status-pill\s*\{(?=[^}]*background:\s*var\(--canvas\);)(?=[^}]*color:\s*var\(--muted\);)[^}]*\}/s,
    );
  });

  it("keeps the real React Flow canvas visible inside the graph stage", () => {
    expect(themeCss).toMatch(
      /\.graph-stage\s*\{[^}]*height:\s*100%;[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto;/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s*\{[^}]*display:\s*grid;[^}]*grid-template-rows:\s*minmax\(0,\s*1fr\);/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-view\s*\{[^}]*height:\s*100%;[^}]*min-height:\s*0;[^}]*display:\s*grid;/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.graph-canvas-panel\s*\{[^}]*height:\s*100%;[^}]*grid-template-rows:\s*minmax\(0,\s*1fr\);/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.graph-canvas-panel\.has-header\s*\{[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.graph-canvas-shell\s*\{[^}]*min-height:\s*0;[^}]*height:\s*100%;[^}]*background:[^}]*var\(--canvas\)/s,
    );
  });

  it("renders graph reading summary cards as dark panels in the hybrid graph stage", () => {
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.canvas-reading-summary\s*\{[^}]*background:\s*var\(--panel-soft\);[^}]*color:\s*var\(--text\);/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.canvas-reading-card\s*\{[^}]*background:\s*var\(--panel\);[^}]*color:\s*var\(--text\);/s,
    );
  });

  it("uses container-aware target graph presentation chrome instead of viewport-only toolbar layout", () => {
    expect(themeCss).toMatch(
      /\.graph-view-shell\s*\{[^}]*height:\s*100%;[^}]*grid-template-rows:\s*auto\s+auto\s+minmax\(0,\s*1fr\);[^}]*container-type:\s*inline-size;/s,
    );
    expect(themeCss).toMatch(
      /\.graph-presentation-toolbar\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*minmax\(180px,\s*1fr\)\s+auto;[^}]*align-items:\s*center;/s,
    );
    expect(themeCss).toMatch(
      /\.graph-view-body\s*\{[^}]*min-height:\s*0;[^}]*display:\s*flex;[^}]*flex-direction:\s*column;/s,
    );
    expect(themeCss).toMatch(
      /@container\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.graph-presentation-toolbar\s*\{[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)\s+auto;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@container\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.graph-presentation-actions\s*\{[\s\S]*?justify-content:\s*flex-end;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /\.graph-canvas-lanes\s*\{[^}]*position:\s*absolute;[^}]*pointer-events:\s*none;/s,
    );
    expect(themeCss).toMatch(
      /\.graph-flow-surface\s*\{[^}]*position:\s*absolute;[^}]*z-index:\s*2;/s,
    );
  });

  it("renders class usage search results as a compact bottom dock without replacing the graph canvas", () => {
    expect(themeCss).toMatch(
      /\.class-diagram-workspace\.has-usage-panel\s*\{(?=[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);)(?=[^}]*grid-template-rows:\s*minmax\(360px,\s*1fr\)\s+minmax\(160px,\s*min\(28%,\s*240px\)\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.class-diagram-workspace\s*\{(?=[^}]*height:\s*100%;)(?=[^}]*display:\s*grid;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.class-diagram-usage-dock\s*\{(?=[^}]*min-height:\s*0;)(?=[^}]*overflow:\s*hidden;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.class-usage-panel\s*\{(?=[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\);)(?=[^}]*overflow:\s*hidden;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.class-usage-group-list\s*\{(?=[^}]*grid-template-columns:\s*repeat\(auto-fit,\s*minmax\(260px,\s*1fr\)\);)(?=[^}]*overflow:\s*auto;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /@container\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.class-diagram-action-strip\s*\{[\s\S]*?justify-content:\s*flex-start;[\s\S]*?\}[\s\S]*?\.class-diagram-action-strip\s+\.ghost-button\s*\{[\s\S]*?width:\s*auto;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@container\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.class-diagram-workspace\.has-usage-panel\s*\{[\s\S]*?grid-template-rows:\s*minmax\(260px,\s*1fr\)\s+minmax\(150px,\s*min\(34%,\s*220px\)\);[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@container\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.class-diagram-workspace\s*\{[\s\S]*?height:\s*min\(440px,\s*44dvh\);[\s\S]*?\}/s,
    );
  });

  it("renders fact graph grouping frames as viewport-bound outlines instead of filled canvas backgrounds", () => {
    expect(themeCss).toMatch(
      /\.graph-canvas-lane\s*\{(?=[^}]*position:\s*absolute;)(?=[^}]*border:\s*1px\s+solid)(?=[^}]*background:\s*transparent;)[^}]*\}/s,
    );
    expect(themeCss).not.toMatch(/\.graph-canvas-lane\s*\{[^}]*color-mix/s);
    expect(themeCss).not.toMatch(/\.architecture-layer-frame\b/);
    expect(themeCss).not.toMatch(/\.architecture-layer-overlay\b/);
  });

  it("keeps React Flow node cards readable on the dark graph canvas", () => {
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.flow-node-card\s*\{[^}]*background:\s*var\(--panel\);[^}]*color:\s*var\(--text\);/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+:is\([^)]*\.flowchart-node-title[^)]*\.flowchart-node-detail[^)]*\.flow-node-owner[^)]*\.flow-node-signature[^)]*\)\s*\{[^}]*color:\s*var\(--text\);/s,
    );
  });

  it("keeps graph context menu actions readable on the dark graph canvas", () => {
    expect(themeCss).toMatch(
      /\.canvas-context-menu\s*\{(?=[^}]*background:\s*var\(--panel\);)(?=[^}]*color:\s*var\(--text\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.canvas-context-menu\s+button\s*\{(?=[^}]*background:\s*var\(--panel-soft\);)(?=[^}]*color:\s*var\(--text\);)(?=[^}]*border-color:\s*var\(--line\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.canvas-context-menu,\s*\.graph-stage-canvas\s+\.canvas-edge-action-bar\s*\{[^}]*background:\s*var\(--panel\);[^}]*color:\s*var\(--text\);/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.canvas-context-menu\s+button\s*\{(?=[^}]*background:\s*var\(--panel-soft\);)(?=[^}]*color:\s*var\(--text\);)(?=[^}]*border-color:\s*var\(--line\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.canvas-context-menu\s+button:is\(:hover,\s*:focus-visible\)\s*\{(?=[^}]*background:\s*var\(--panel-strong\);)(?=[^}]*color:\s*var\(--text\);)(?=[^}]*border-color:\s*var\(--accent\);)[^}]*\}/s,
    );
  });

  it("preserves flowchart node geometry inside the hybrid graph stage", () => {
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.flowchart-react-node\.kind-decision\s*\{[^}]*width:\s*100%;[^}]*height:\s*100%;[^}]*min-height:\s*228px;/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.flowchart-node-card\.kind-decision\s*\{[^}]*width:\s*100%;[^}]*max-width:\s*calc\(100%\s*-\s*48px\);/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.flowchart-rf-node\.kind-decision\s*\{[^}]*overflow:\s*visible;/s,
    );
    expect(themeCss).not.toMatch(
      /\.graph-stage-canvas\s+\.flowchart-node-card\.kind-decision\s*\{[^}]*width:\s*74%;/s,
    );
  });

  it("keeps hybrid decision nodes drawn by the diamond shell instead of a rectangular inner card", () => {
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.flowchart-react-node\.kind-decision\s*>\s*\.flowchart-node-card\s*\{[^}]*background:\s*transparent;[^}]*box-shadow:\s*none;/s,
    );
    expect(themeCss).toMatch(
      /\.graph-stage-canvas\s+\.flowchart-react-node\.kind-decision::before\s*\{[^}]*background:\s*linear-gradient\(145deg,\s*rgba\(245,\s*158,\s*11,\s*0\.18\),\s*var\(--panel\)\);/s,
    );
  });

  it("uses right-rail container rules so the assistant thread and composer do not collapse", () => {
    const removedStagePanelClass = ["stage", "workbench", "panel"].join("-");
    expect(themeCss).not.toContain(`.${removedStagePanelClass}`);
    expect(themeCss).toMatch(/\.link-outline\s*\{[^}]*height:\s*100%;/s);
    expect(themeCss).toMatch(
      /\.hybrid-workbench-assistant-slot\s*\{[^}]*grid-column:\s*3;[^}]*grid-row:\s*1;/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-workbench-shell\s*\{[^}]*height:\s*100%;[^}]*display:\s*grid;/s,
    );
    expect(themeCss).toMatch(
      /@container\s*\(max-width:\s*520px\)\s*\{[\s\S]*?\.assistant-send-type-selector\s*\{[\s\S]*?grid-template-columns:\s*1fr\s+1fr;[\s\S]*?\}/s,
    );
  });

  it("uses IDE-like resizable columns instead of fixed sidebars", () => {
    expect(themeCss).toMatch(
      /\.hybrid-workbench-layout\s*\{[^}]*grid-template-columns:\s*var\(--outline-width\)\s+minmax\(0,\s*1fr\)\s+var\(--workbench-width\);/s,
    );
    expect(themeCss).toMatch(
      /\.hybrid-workbench-outline-slot\s*\{(?=[^}]*grid-column:\s*1;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.hybrid-workbench-graph-slot\s*\{(?=[^}]*grid-column:\s*2;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.hybrid-workbench-assistant-slot\s*\{(?=[^}]*grid-column:\s*3;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.hybrid-workbench-layout\.outline-collapsed\s+\.hybrid-workbench-outline-slot\s*\{[^}]*width:\s*var\(--outline-width\);/s,
    );
    expect(themeCss).not.toMatch(/\.hybrid-workbench-layout\.workbench-collapsed/);
    expect(themeCss).toMatch(
      /\.hybrid-workbench-resizer\s*\{(?=[^}]*cursor:\s*col-resize;)(?=[^}]*grid-column:\s*3;)[^}]*\}/s,
    );
    expect(themeCss).not.toMatch(
      /\.hybrid-workbench-layout\s*\{[^}]*grid-template-columns:\s*minmax\(220px,\s*0\.72fr\)\s+minmax\(560px,\s*2fr\)\s+minmax\(320px,\s*0\.96fr\);/s,
    );
  });

  it("keeps the assistant workbench to a single shell with card-level content", () => {
    const removedShellClass = ["workbench", "shell"].join("-");
    expect(themeCss).toMatch(
      /\.assistant-workbench-shell\s*\{[^}]*border-left:\s*1px\s+solid\s+var\(--line\);[^}]*background:\s*var\(--shell\);/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-turn-group\s*\{[^}]*display:\s*grid;[^}]*gap:\s*12px;/s,
    );
    expect(themeCss).toMatch(
      /\.assistant-card-flow\s*\{[^}]*display:\s*grid;[^}]*gap:\s*10px;/s,
    );
    expect(themeCss).not.toContain(`.${removedShellClass}`);
  });

  it("prioritizes the graph stage over fixed sidebars on embedded desktop widths", () => {
    expect(themeCss).toMatch(
      /\.hybrid-workbench-layout\s*\{[^}]*grid-template-columns:\s*var\(--outline-width\)\s+minmax\(0,\s*1fr\)\s+var\(--workbench-width\);/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*1440px\)\s+and\s+\(min-width:\s*1061px\)\s*\{[\s\S]*?\.workflow-taskbar-actions\s*\{[\s\S]*?grid-column:\s*1\s*\/\s*-1;[\s\S]*?\.hybrid-workbench-layout\s*\{[\s\S]*?--outline-width:\s*300px;[\s\S]*?--workbench-width:\s*420px;/s,
    );
  });

  it("lets link outline rows wrap real identifiers instead of hiding them behind ellipses", () => {
    expect(themeCss).toMatch(
      /\.link-outline-row\s*\{[^}]*grid-template-columns:\s*42px\s+minmax\(0,\s*1fr\);[^}]*align-items:\s*start;/s,
    );
    expect(themeCss).toMatch(
      /\.link-outline-meta-row\s*\{[^}]*display:\s*flex;[^}]*flex-wrap:\s*wrap;/s,
    );
    expect(themeCss).toMatch(
      /\.link-outline-label,\s*\.link-outline-meta\s*\{[^}]*white-space:\s*normal;[^}]*overflow-wrap:\s*anywhere;/s,
    );
    expect(themeCss).not.toMatch(/\.link-outline-label,\s*\.link-outline-meta\s*\{[^}]*text-overflow:\s*ellipsis;[^}]*white-space:\s*nowrap;/s);
  });

  it("prevents mobile horizontal overflow in the stacked hybrid workbench", () => {
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.app-shell\.graph-workbench\s*\{(?=[\s\S]*?width:\s*100%;)(?=[\s\S]*?padding:\s*10px\s+0\s+0;)(?=[\s\S]*?overflow-x:\s*hidden;)[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.hybrid-workbench-layout,\s*\.hybrid-workbench-outline-slot,\s*\.hybrid-workbench-graph-slot,\s*\.hybrid-workbench-assistant-slot\s*\{[\s\S]*?width:\s*100%;[\s\S]*?max-width:\s*100%;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.graph-stage-canvas\s+\.canvas-reading-grid\s*\{[\s\S]*?grid-template-columns:\s*1fr;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.graph-stage-canvas\s+\.react-flow__node\s*\{[\s\S]*?max-width:\s*calc\(100vw\s*-\s*48px\);[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.workflow-taskbar-path,\s*\.graph-stage-title\s+p,\s*\.canvas-reading-detail,\s*\.flowchart-node-detail\s*\{[\s\S]*?white-space:\s*normal;[\s\S]*?overflow-wrap:\s*anywhere;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.workflow-taskbar-actions\s*\{(?=[\s\S]*?flex-wrap:\s*nowrap;)(?=[\s\S]*?overflow-x:\s*auto;)[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.workflow-taskbar-actions\s+:is\(\.primary-button,\s*\.ghost-button\)\s*\{(?=[\s\S]*?width:\s*auto;)(?=[\s\S]*?flex:\s*0\s+0\s+auto;)[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.view-switch\s*\{[\s\S]*?grid-template-columns:\s*repeat\(6,\s*minmax\(0,\s*1fr\)\);[\s\S]*?\}/s,
    );
  });
});

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

  it("keeps legacy workbench content readable inside the dark stage workbench", () => {
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-panel-body\s*\{[^}]*background:\s*var\(--panel-soft\);[^}]*color:\s*var\(--text\);/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-section-card,\s*\.stage-workbench-panel\s+\.qa-page-panel\s*\{(?=[^}]*background:\s*transparent;)(?=[^}]*box-shadow:\s*none;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+:is\([^)]*\.workbench-step-list[^)]*\.workbench-step-detail[^)]*\.workbench-candidate-list[^)]*\.workbench-draft-section[^)]*\.workbench-qa-thread[^)]*\)\s*\{(?=[^}]*background:\s*transparent;)(?=[^}]*color:\s*var\(--text\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+:is\([^)]*\.workbench-step-item[^)]*\.workbench-candidate-card[^)]*\.workbench-draft-card[^)]*\.workbench-empty-card[^)]*\)\s*\{[^}]*background:\s*var\(--panel-soft\);[^}]*color:\s*var\(--text\);/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-chat-message\.user\s*\{[^}]*background:\s*var\(--panel-soft\);[^}]*color:\s*var\(--text\);/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-chat-message\.assistant\s*\{[^}]*background:\s*transparent;[^}]*color:\s*var\(--text\);/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+:is\([^)]*\.qa-rich-section[^)]*\.qa-rich-section-summary\.expanded[^)]*\.qa-rich-section\.risk[^)]*\.qa-rich-section\.suggestion[^)]*\)\s*\{(?=[^}]*background:\s*transparent;)(?=[^}]*color:\s*var\(--text\);)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+:is\([^)]*\.side-panel[^)]*\.generation-plan-panel[^)]*\.workbench-section-card-body[^)]*\.workbench-chat-empty[^)]*\.warning-list[^)]*\)\s*\{[^}]*color:\s*var\(--text\);/s,
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
      /@container\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.graph-presentation-toolbar\s*\{[\s\S]*?grid-template-columns:\s*1fr;[\s\S]*?\}/s,
    );
    expect(themeCss).toMatch(
      /\.graph-canvas-lanes\s*\{[^}]*position:\s*absolute;[^}]*pointer-events:\s*none;/s,
    );
    expect(themeCss).toMatch(
      /\.graph-flow-surface\s*\{[^}]*position:\s*absolute;[^}]*z-index:\s*2;/s,
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

  it("uses right-workbench container rules so reused panels do not collapse into vertical text", () => {
    expect(themeCss).toMatch(/\.stage-workbench\s*\{[^}]*height:\s*100%;/s);
    expect(themeCss).toMatch(/\.link-outline\s*\{[^}]*height:\s*100%;/s);
    expect(themeCss).toMatch(/\.stage-workbench-panel\s*\{[^}]*container-type:\s*inline-size;/s);
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-shell\s*\{[^}]*height:\s*100%;/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-shell\s*\{[^}]*grid-template-rows:\s*minmax\(0,\s*1fr\);[^}]*gap:\s*0;/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-panel-body\s*\{[^}]*height:\s*100%;/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s*\{[^}]*height:\s*100%;[^}]*display:\s*grid;[^}]*grid-template-rows:\s*minmax\(0,\s*1fr\);/s,
    );
    expect(themeCss).toMatch(
      /@container\s*\(max-width:\s*520px\)\s*\{[\s\S]*?\.stage-workbench-panel\s+\.explanation-layout\s*\{[\s\S]*?grid-template-columns:\s*1fr;[\s\S]*?\}/s,
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
      /\.hybrid-workbench-stage-slot\s*\{(?=[^}]*grid-column:\s*3;)[^}]*\}/s,
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

  it("keeps the right workbench to three visual layers without nested card shells", () => {
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-section-card,\s*\.stage-workbench-panel\s+\.qa-page-panel\s*\{(?=[^}]*border-width:\s*0;)(?=[^}]*border-radius:\s*0;)(?=[^}]*background:\s*transparent;)(?=[^}]*box-shadow:\s*none;)[^}]*\}/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-chat-stream\s*\{[^}]*border-width:\s*0;[^}]*background:\s*transparent;/s,
    );
    expect(themeCss).toMatch(
      /\.stage-workbench-panel\s+\.workbench-chat-message\.assistant\s*\{[^}]*border-width:\s*0;[^}]*background:\s*transparent;[^}]*box-shadow:\s*none;/s,
    );
    expect(themeCss).not.toMatch(
      /\.stage-workbench-panel\s+:is\([^)]*\.workbench-section-card[^)]*\.qa-page-panel[^)]*\.workbench-step-list[^)]*\.workbench-step-detail[^)]*\.workbench-candidate-list[^)]*\)\s*\{[^}]*background:\s*var\(--panel\);/s,
    );
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
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.hybrid-workbench-layout,\s*\.hybrid-workbench-outline-slot,\s*\.hybrid-workbench-graph-slot,\s*\.hybrid-workbench-stage-slot\s*\{[\s\S]*?width:\s*100%;[\s\S]*?max-width:\s*100%;[\s\S]*?\}/s,
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
      /@media\s*\(max-width:\s*720px\)\s*\{[\s\S]*?\.workflow-taskbar-actions,\s*\.change-tray-actions\s*\{[\s\S]*?grid-template-columns:\s*1fr;[\s\S]*?\}/s,
    );
  });
});

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";

const REPO_ROOT = path.resolve(__dirname, "../../../..");
const API_PATH = path.join(REPO_ROOT, "web/src/app/api.ts");
const BROWSER_PANEL_PATH = path.join(REPO_ROOT, "src/main/kotlin/com/charmnight/linkgraph/ui/GraphBrowserPanel.kt");

function bridgeMethodsDeclaredInApi(source: string): string[] {
  const interfaceMatch = source.match(/linkGraphBridge\?: \{([\s\S]*?)\n    \};/);
  if (!interfaceMatch) {
    throw new Error("Failed to locate linkGraphBridge interface in api.ts");
  }
  return Array.from(interfaceMatch[1].matchAll(/^\s+([A-Za-z0-9_]+)\?:\s*\(/gm), (match) => match[1]);
}

function injectedBridgeMethods(source: string): Map<string, string> {
  const scriptMatch = source.match(/window\.linkGraphBridge = \{([\s\S]*?)\n\s*\};/);
  if (!scriptMatch) {
    throw new Error("Failed to locate linkGraphBridge injection script in GraphBrowserPanel.kt");
  }
  return new Map(
    Array.from(
      scriptMatch[1].matchAll(/^\s+([A-Za-z0-9_]+):\s*\([\s\S]*?\$\{([A-Za-z0-9_]+)\?\.inject/gm),
      (match) => [match[1], match[2]],
    ),
  );
}

function registeredHandlerQueries(source: string): Set<string> {
  return new Set(Array.from(source.matchAll(/([A-Za-z0-9_]+Query)\?\.addHandler/g), (match) => match[1]));
}

describe("bridge contract", () => {
  it("injects every frontend-declared bridge method into the browser runtime", () => {
    const apiSource = readFileSync(API_PATH, "utf8");
    const browserPanelSource = readFileSync(BROWSER_PANEL_PATH, "utf8");

    const declaredMethods = bridgeMethodsDeclaredInApi(apiSource);
    const injectedMethods = new Set(injectedBridgeMethods(browserPanelSource).keys());

    expect([...declaredMethods].filter((method) => !injectedMethods.has(method))).toEqual([]);
  });

  it("backs every injected runtime bridge method with a registered JCEF handler", () => {
    const browserPanelSource = readFileSync(BROWSER_PANEL_PATH, "utf8");

    const methodToQuery = injectedBridgeMethods(browserPanelSource);
    const registeredQueries = registeredHandlerQueries(browserPanelSource);

    expect(
      [...methodToQuery.entries()].filter(([, queryName]) => !registeredQueries.has(queryName)),
    ).toEqual([]);
  });
});

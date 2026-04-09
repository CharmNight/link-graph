import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./app/App";
import { traceLinkGraph } from "./app/debug";
import "./app/theme.css";

window.addEventListener("error", (event) => {
  traceLinkGraph("main.windowError", {
    message: event.message,
    error: String(event.error),
  });
  console.error("link-graph window error", event.message, event.error);
});

window.addEventListener("unhandledrejection", (event) => {
  traceLinkGraph("main.unhandledRejection", {
    reason: String(event.reason),
  });
  console.error("link-graph unhandled rejection", event.reason);
});

try {
  const rootElement = document.getElementById("root");
  if (!rootElement) {
    throw new Error("link-graph root element 未找到");
  }
  ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
  traceLinkGraph("main.renderDispatched", {
    hasRootElement: true,
  });
} catch (error) {
  traceLinkGraph("main.renderFailed", {
    error: String(error),
  });
  console.error("link-graph react render failed", error);
  throw error;
}

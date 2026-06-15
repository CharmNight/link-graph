import { describe, it } from "vitest";
import {
  requestCodeDraftsAsync,
} from "../../app/api";
import {
  expectBridgeCommand,
  installBridgeCommandSpy,
} from "./bridgeTestUtils";

describe("api async naming", () => {
  it("uses async-named helper for code-draft requests", () => {
    installBridgeCommandSpy();

    requestCodeDraftsAsync();

    expectBridgeCommand("requestCodeDrafts", {});
  });
});

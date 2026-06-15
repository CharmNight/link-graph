import { expect, vi } from "vitest";
import type { BridgeCommandEnvelope, BridgeCommandType } from "../../app/api";

export function installBridgeCommandSpy() {
  const sendCommand = vi.fn();
  window.linkGraphBridge = {
    sendCommand,
  };
  return sendCommand;
}

export function bridgeCommands(type?: BridgeCommandType): BridgeCommandEnvelope[] {
  const sendCommand = window.linkGraphBridge?.sendCommand as ReturnType<typeof vi.fn> | undefined;
  return (sendCommand?.mock.calls ?? [])
    .map((call) => call[0] as BridgeCommandEnvelope)
    .filter((command) => !type || command.type === type);
}

export function expectBridgeCommand(type: BridgeCommandType, payload: unknown = {}): void {
  expect(bridgeCommands(type)).toEqual(expect.arrayContaining([
    {
      schemaVersion: 1,
      type,
      payload,
    },
  ]));
}

export function expectBridgeCommandCount(type: BridgeCommandType, count: number): void {
  expect(bridgeCommands(type)).toHaveLength(count);
}

export function expectNoBridgeCommand(type: BridgeCommandType): void {
  expectBridgeCommandCount(type, 0);
}

import "@testing-library/jest-dom/vitest";

if (typeof globalThis.ResizeObserver === "undefined") {
  class ResizeObserverStub implements ResizeObserver {
    observe(): void {
      return undefined;
    }

    unobserve(): void {
      return undefined;
    }

    disconnect(): void {
      return undefined;
    }
  }

  Object.defineProperty(globalThis, "ResizeObserver", {
    configurable: true,
    writable: true,
    value: ResizeObserverStub,
  });
}

if (typeof globalThis.DOMMatrixReadOnly === "undefined") {
  class DOMMatrixReadOnlyStub {
    readonly m22: number;

    constructor(transform = "") {
      const source = typeof transform === "string" ? transform : "";
      const matrixValues = source.match(/matrix\(([^)]+)\)/)?.[1]
        ?.split(",")
        .map((value) => Number.parseFloat(value.trim()));
      this.m22 = Number.isFinite(matrixValues?.[3]) ? matrixValues[3]! : 1;
    }
  }

  Object.defineProperty(globalThis, "DOMMatrixReadOnly", {
    configurable: true,
    writable: true,
    value: DOMMatrixReadOnlyStub,
  });
}

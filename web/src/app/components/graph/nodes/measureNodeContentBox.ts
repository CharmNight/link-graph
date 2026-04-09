interface NodeContentBox {
  width: number;
  height: number;
}

function validBox(width: number, height: number): NodeContentBox | null {
  if (!Number.isFinite(width) || !Number.isFinite(height)) {
    return null;
  }
  if (width <= 0 || height <= 0) {
    return null;
  }
  return { width, height };
}

export function measureNodeContentBox(element: HTMLElement | null): NodeContentBox | null {
  if (!element) {
    return null;
  }
  const offsetBox = validBox(element.offsetWidth, element.offsetHeight);
  if (offsetBox) {
    return offsetBox;
  }
  const clientBox = validBox(element.clientWidth, element.clientHeight);
  if (clientBox) {
    return clientBox;
  }
  const rect = element.getBoundingClientRect();
  return validBox(rect.width, rect.height);
}

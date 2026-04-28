export interface NodeMeasuredSize {
  width: number;
  height: number;
}

type NodeSizeRegistryListener = () => void;

function normalizeSize(size: NodeMeasuredSize): NodeMeasuredSize | null {
  if (!Number.isFinite(size.width) || !Number.isFinite(size.height)) {
    return null;
  }
  if (size.width <= 0 || size.height <= 0) {
    return null;
  }
  return {
    width: Math.round(size.width),
    height: Math.round(size.height),
  };
}

export class NodeSizeRegistry {
  private readonly sizes = new Map<string, NodeMeasuredSize>();
  private readonly listeners = new Set<NodeSizeRegistryListener>();
  private readonly reporters = new Map<string, (size: NodeMeasuredSize) => void>();
  private revision = 0;

  private emitChange(): void {
    this.revision += 1;
    this.listeners.forEach((listener) => listener());
  }

  get(nodeId: string): NodeMeasuredSize | undefined {
    return this.sizes.get(nodeId);
  }

  set(nodeId: string, size: NodeMeasuredSize): boolean {
    const normalized = normalizeSize(size);
    if (!normalized) {
      return false;
    }
    const current = this.sizes.get(nodeId);
    if (current && current.width === normalized.width && current.height === normalized.height) {
      return false;
    }
    this.sizes.set(nodeId, normalized);
    this.emitChange();
    return true;
  }

  clear(nodeId?: string): void {
    if (nodeId) {
      if (!this.sizes.has(nodeId)) {
        return;
      }
      this.sizes.delete(nodeId);
      this.emitChange();
      return;
    }
    if (this.sizes.size === 0) {
      return;
    }
    this.sizes.clear();
    this.emitChange();
  }

  snapshot(): ReadonlyMap<string, NodeMeasuredSize> {
    return new Map(this.sizes);
  }

  currentRevision(): number {
    return this.revision;
  }

  subscribe(listener: NodeSizeRegistryListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  reporter(nodeId: string): (size: NodeMeasuredSize) => void {
    const cachedReporter = this.reporters.get(nodeId);
    if (cachedReporter) {
      return cachedReporter;
    }
    const nextReporter = (size: NodeMeasuredSize) => {
      this.set(nodeId, size);
    };
    this.reporters.set(nodeId, nextReporter);
    return nextReporter;
  }
}

export function createNodeSizeRegistry(): NodeSizeRegistry {
  return new NodeSizeRegistry();
}

export const defaultNodeSizeRegistry = createNodeSizeRegistry();

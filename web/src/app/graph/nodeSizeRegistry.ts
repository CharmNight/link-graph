// 节点尺寸注册表：集中管理图节点的实际渲染尺寸（width × height）。
// 主要使用场景：React Flow 的布局算法需要知道每个节点的真实尺寸来计算位置与避让；
// 而节点尺寸由 React 渲染后才能测得，因此需要这样一个外部注册表把"渲染后"和"布局前"两个阶段解耦。

/** 已测量的节点尺寸。 */
export interface NodeMeasuredSize {
  width: number;
  height: number;
}

/** 注册表变更监听器；变更时被调用。 */
type NodeSizeRegistryListener = () => void;

/**
 * 规范化尺寸：校验数值合法并取整。
 * 非有限数或非正数返回 null，让调用方知道这个尺寸无效。
 */
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

/**
 * 节点尺寸注册表。
 *
 * 维护 nodeId → NodeMeasuredSize 的映射，支持：
 * - 读写单个节点尺寸；
 * - 订阅变更（让布局算法可以响应尺寸变化）；
 * - 派发稳定的 reporter 回调（避免每个节点重复创建闭包）。
 *
 * 注册表内部维护 revision 单调递增，每次变更都 +1，
 * 让订阅者可以做"是否真的变了"的快速判断。
 */
export class NodeSizeRegistry {
  /** nodeId → 尺寸 的核心映射。 */
  private readonly sizes = new Map<string, NodeMeasuredSize>();
  /** 当前订阅者集合。 */
  private readonly listeners = new Set<NodeSizeRegistryListener>();
  /** nodeId → reporter 回调 的缓存，避免重复创建。 */
  private readonly reporters = new Map<string, (size: NodeMeasuredSize) => void>();
  /** 单调递增的修订号；每次变更 +1。 */
  private revision = 0;

  /** 内部：派发变更通知给所有订阅者。 */
  private emitChange(): void {
    this.revision += 1;
    this.listeners.forEach((listener) => listener());
  }

  /** 取某节点的当前尺寸；未注册时返回 undefined。 */
  get(nodeId: string): NodeMeasuredSize | undefined {
    return this.sizes.get(nodeId);
  }

  /**
   * 写入某节点的尺寸。
   * 无效尺寸或与当前相同都返回 false（不触发变更）。
   */
  set(nodeId: string, size: NodeMeasuredSize): boolean {
    const normalized = normalizeSize(size);
    if (!normalized) {
      return false;
    }
    // 与当前值相同时不触发变更，避免无意义的重渲染
    const current = this.sizes.get(nodeId);
    if (current && current.width === normalized.width && current.height === normalized.height) {
      return false;
    }
    this.sizes.set(nodeId, normalized);
    this.emitChange();
    return true;
  }

  /**
   * 清除尺寸记录。
   * - 传入 nodeId：只清除该节点；
   * - 不传：清除全部。
   * 已不存在 / 已为空时不触发变更。
   */
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

  /** 返回当前所有尺寸的快照（拷贝，外部修改不影响注册表内部）。 */
  snapshot(): ReadonlyMap<string, NodeMeasuredSize> {
    return new Map(this.sizes);
  }

  /** 取当前修订号。 */
  currentRevision(): number {
    return this.revision;
  }

  /**
   * 订阅注册表变更。
   * 返回取消订阅函数；调用方应在卸载时调用避免泄漏。
   */
  subscribe(listener: NodeSizeRegistryListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * 取某节点的 reporter 回调。
   * 同一 nodeId 多次调用会返回同一个函数引用，便于 React useRef 稳定化。
   */
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

/** 工厂函数：创建一个新的注册表实例。 */
export function createNodeSizeRegistry(): NodeSizeRegistry {
  return new NodeSizeRegistry();
}

/** 默认共享的单例注册表。 */
export const defaultNodeSizeRegistry = createNodeSizeRegistry();

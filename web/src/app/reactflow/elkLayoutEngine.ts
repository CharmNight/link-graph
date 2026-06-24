// ELK 布局引擎的工厂：根据运行环境选择 Web Worker 或 bundled 实现。
// 主要场景：浏览器环境优先用 Web Worker（不阻塞主线程）；非浏览器环境退回 bundled。
import WorkerElk from "elkjs/lib/elk-api";
import type { ELK } from "elkjs/lib/elk-api";
import elkWorkerUrl from "elkjs/lib/elk-worker.min.js?url";

/** 工厂环境：只需要 Worker 构造器（用于测试注入）。 */
interface ElkWorkerFactoryEnvironment {
  Worker?: typeof Worker;
}

/**
 * 创建 ELK Worker 工厂。
 * 环境不支持 Worker 时返回 null（调用方据此决定是否走 bundled 兜底）。
 */
export function createElkWorkerFactory(
  environment: ElkWorkerFactoryEnvironment = globalThis,
  workerUrl: string = elkWorkerUrl,
): ((url?: string) => Worker) | null {
  if (!environment.Worker) {
    return null;
  }
  return () => new environment.Worker!(workerUrl);
}

/**
 * 创建 ELK 布局引擎实例。
 *
 * 流程：
 * 1) 尝试创建 Worker 工厂；成功则用 WorkerElk（推荐，主线程不阻塞）；
 * 2) 不支持 Worker 且为开发/测试环境：走 bundled 兜底；
 * 3) 不支持 Worker 且为生产环境：抛错（让用户感知环境限制）。
 *
 * @return ELK 引擎实例
 */
export async function createElkLayoutEngine(
  environment: ElkWorkerFactoryEnvironment = globalThis,
  workerUrl: string = elkWorkerUrl,
): Promise<ELK> {
  const workerFactory = createElkWorkerFactory(environment, workerUrl);
  if (!workerFactory) {
    // 开发/测试环境：允许走 bundled 兜底（避免测试因 Worker 缺失而失败）
    if (import.meta.env.DEV || import.meta.env.MODE === "test") {
      return createBundledElkLayoutEngine();
    }
    throw new Error("当前浏览器环境不支持 Web Worker，无法运行 ELK 图布局。");
  }
  try {
    return new WorkerElk({ workerFactory });
  } catch (error) {
    // Worker 创建失败：仅开发/测试环境兜底；生产环境直接抛错
    if (!(import.meta.env.DEV || import.meta.env.MODE === "test")) {
      throw error;
    }
    return createBundledElkLayoutEngine();
  }
}

/**
 * 创建 bundled ELK 引擎（同步包，无 Worker）。
 * 仅用于开发/测试兜底——bundled 包体积较大，不适合生产环境。
 */
async function createBundledElkLayoutEngine(): Promise<ELK> {
  const { default: BundledElk } = await import("elkjs/lib/elk.bundled.js");
  return new BundledElk();
}

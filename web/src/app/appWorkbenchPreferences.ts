// 工作台布局偏好持久化：将"大纲是否折叠"和"助理面板宽度"保存到 localStorage，
// 让用户下次打开工具窗口时恢复上次布局。

/** localStorage 中存储布局偏好的键名。 */
export const WORKBENCH_LAYOUT_STORAGE_KEY = "linkGraph.hybridWorkbenchLayout";
/** 助理工作台默认宽度，平衡助理面板与图谱可视区的初始比例。 */
export const DEFAULT_ASSISTANT_WORKBENCH_WIDTH = 420;

/** 助理工作台最小宽度，再小会导致助理消息与控件拥挤。 */
const MIN_ASSISTANT_WORKBENCH_WIDTH = 320;
/** 助理工作台最大宽度，超过会挤压图谱可视区。 */
const MAX_ASSISTANT_WORKBENCH_WIDTH = 720;

/** 用户可持久化的混合工作台布局偏好。 */
export interface HybridWorkbenchLayoutPreference {
  /** 左侧大纲面板是否折叠收起。 */
  outlineCollapsed: boolean;
  /** 助理工作台的像素宽度，已做范围约束。 */
  workbenchWidth: number;
}

/** 仅依赖 getItem/setItem 的存储抽象，便于在非浏览器环境注入测试替身。 */
type WorkbenchLayoutStorage = Pick<Storage, "getItem" | "setItem">;

/** 默认偏好：大纲展开，工作台宽度取默认值。 */
function defaultHybridWorkbenchLayoutPreference(): HybridWorkbenchLayoutPreference {
  return {
    outlineCollapsed: false,
    workbenchWidth: DEFAULT_ASSISTANT_WORKBENCH_WIDTH,
  };
}

/**
 * 取得浏览器 localStorage 引用。
 * SSR 或浏览器禁用存储（隐私模式等）时返回 null，调用方需要处理缺失情况。
 */
function browserLayoutStorage(): WorkbenchLayoutStorage | null {
  // 在非浏览器（如测试）环境下不访问 window
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return window.localStorage;
  } catch {
    // 访问 localStorage 可能抛出 SecurityError，统一吞掉返回 null
    return null;
  }
}

/**
 * 把任意宽度约束到合法范围并取整，避免偏好文件中存入非法值导致 UI 错乱。
 */
export function clampAssistantWorkbenchWidth(width: number): number {
  return Math.max(MIN_ASSISTANT_WORKBENCH_WIDTH, Math.min(MAX_ASSISTANT_WORKBENCH_WIDTH, Math.round(width)));
}

/**
 * 从给定存储读取布局偏好；读取失败或缺失时返回默认值。
 * 解析时只信任结构正确的字段，避免被外部手动篡改的 localStorage 干扰。
 */
export function readHybridWorkbenchLayoutPreference(
  storage: WorkbenchLayoutStorage | null = browserLayoutStorage(),
): HybridWorkbenchLayoutPreference {
  if (!storage) {
    return defaultHybridWorkbenchLayoutPreference();
  }
  try {
    const raw = storage.getItem(WORKBENCH_LAYOUT_STORAGE_KEY);
    if (!raw) {
      return defaultHybridWorkbenchLayoutPreference();
    }
    const parsed = JSON.parse(raw) as Partial<HybridWorkbenchLayoutPreference>;
    // 严格校验每个字段，确保只有合法值才被采用
    return {
      outlineCollapsed: parsed.outlineCollapsed === true,
      workbenchWidth: typeof parsed.workbenchWidth === "number"
        ? clampAssistantWorkbenchWidth(parsed.workbenchWidth)
        : DEFAULT_ASSISTANT_WORKBENCH_WIDTH,
    };
  } catch {
    // JSON 解析失败或访问异常都退回到默认值
    return defaultHybridWorkbenchLayoutPreference();
  }
}

/**
 * 把布局偏好写入存储。写入前同样对宽度和布尔值做约束，
 * 避免运行时的临时非法状态被持久化。
 * 任何异常都被吞掉，因为布局持久化只是体验增强，不应阻塞渲染。
 */
export function writeHybridWorkbenchLayoutPreference(
  preference: HybridWorkbenchLayoutPreference,
  storage: WorkbenchLayoutStorage | null = browserLayoutStorage(),
): void {
  if (!storage) {
    return;
  }
  try {
    storage.setItem(
      WORKBENCH_LAYOUT_STORAGE_KEY,
      JSON.stringify({
        outlineCollapsed: preference.outlineCollapsed === true,
        workbenchWidth: clampAssistantWorkbenchWidth(preference.workbenchWidth),
      }),
    );
  } catch {
    // Layout persistence is a convenience; rendering should not depend on storage availability.
    // 布局持久化是体验增强，渲染不应依赖存储可用性，故静默吞下异常。
  }
}

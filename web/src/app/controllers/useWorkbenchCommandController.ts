import {
  applyCodeDrafts,
  exportMermaid,
  requestAnalysisDisplayMode,
  requestArchitectureGraph,
  requestPackageDependencyGraph,
  requestCodeDraftsAsync,
  requestClassDiagram,
  requestClassUsages,
  requestDraftNavigation,
  requestExpandOverflowNode,
  requestExpandInvocation,
  requestRemoveInvocationExpansion,
  requestOpenSettings,
  requestReviewGraph,
  requestSyncPreview,
  showDiffMode,
} from "../api";
import type { AnalysisDisplayMode, IndexedClassDiagramOptions, IndexedReviewGraphOptions } from "../types";
import type { useBridgeCommandController } from "./useBridgeCommandController";

/** 工作台命令控制器的入参；用于注入桥接命令执行器与各图谱的加载状态。 */
interface UseWorkbenchCommandControllerArgs {
  /** 桥接命令执行器集合，覆盖同步与异步两类派发入口。 */
  bridgeCommands: Pick<
    ReturnType<typeof useBridgeCommandController>,
    "runBridgeCommand" | "submitAsyncBridgeCommand"
  >;
  /** 三类分析图谱在 IntelliJ 后端的当前加载状态；决定切换展示模式时是否需触发首次加载。 */
  availability?: IndexedGraphAvailability;
}

/** 三类核心分析图谱的加载就绪标记，避免重复加载已存在的图。 */
interface IndexedGraphAvailability {
  /** 架构图是否已加载。 */
  architectureGraphLoaded: boolean;
  /** 类图是否已加载。 */
  classDiagramLoaded: boolean;
  /** Review 图是否已加载。 */
  reviewGraphLoaded: boolean;
}

/**
 * 工作台命令派发 Hook。
 *
 * 把工作台菜单/按钮触发的各类"加载图、导出、跳转、草稿处理"动作封装成统一的 handle* 函数，
 * 统一通过桥接命令控制器派发到 IntelliJ 后端，附带对应的成功反馈文案。
 */
export function useWorkbenchCommandController({
  bridgeCommands,
  availability = {
    architectureGraphLoaded: false,
    classDiagramLoaded: false,
    reviewGraphLoaded: false,
  },
}: UseWorkbenchCommandControllerArgs) {
  /**
   * 切换分析展示模式。
   * 对于架构图/类图/Review 图，若目标图尚未加载则改为发起首次加载请求；Review 图带 diff 项时也强制重新加载。
   */
  function handleRequestAnalysisDisplayMode(displayMode: AnalysisDisplayMode, selectedDiffItemIds: string[] = []) {
    if (displayMode === "ARCHITECTURE_GRAPH") {
      if (availability.architectureGraphLoaded) {
        bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
        return;
      }
      bridgeCommands.runBridgeCommand("加载架构图", () => requestArchitectureGraph());
      return;
    }
    if (displayMode === "CLASS_DIAGRAM") {
      if (availability.classDiagramLoaded) {
        bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
        return;
      }
      bridgeCommands.runBridgeCommand("加载类图", () => requestClassDiagram());
      return;
    }
    if (displayMode === "REVIEW_GRAPH") {
      if (availability.reviewGraphLoaded && selectedDiffItemIds.length === 0) {
        bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
        return;
      }
      bridgeCommands.runBridgeCommand("加载 Review Graph", () => requestReviewGraph(selectedDiffItemIds));
      return;
    }
    bridgeCommands.runBridgeCommand("切换展示模式", () => requestAnalysisDisplayMode(displayMode));
  }

  /** 按节点范围加载类图（不传则全量加载）。 */
  function handleRequestClassDiagram(scopeNodeId?: string | null) {
    bridgeCommands.runBridgeCommand("加载类图", () => requestClassDiagram(scopeNodeId ?? null));
  }

  /** 按节点范围 + 自定义渲染选项加载类图（如成员可见性、布局等）。 */
  function handleRequestClassDiagramWithOptions(
    scopeNodeId: string | null | undefined,
    classDiagram: Partial<IndexedClassDiagramOptions>,
  ) {
    bridgeCommands.runBridgeCommand("加载类图", () => requestClassDiagram(scopeNodeId ?? null, { classDiagram }));
  }

  /** 查找指定类的使用处，可附带作用域、签名、虚拟文件路径等过滤参数。 */
  function handleRequestClassUsages(
    targetNodeId: string,
    options: {
      scopeNodeId?: string | null;
      targetQualifiedName?: string | null;
      sourceVirtualFileUrl?: string | null;
      sourcePath?: string | null;
      maxUsageGroups?: number | null;
      maxUsageEntries?: number | null;
      includeImports?: boolean | null;
    } = {},
  ) {
    bridgeCommands.runBridgeCommand("查找类使用处", () => requestClassUsages(targetNodeId, options));
  }

  /** 加载指定包的依赖图，可选是否包含外部库与 JDK。 */
  function handleRequestPackageDependencyGraph(
    packageName?: string | null,
    options: { includeExternalLibraries?: boolean; includeJdk?: boolean } = {},
  ) {
    bridgeCommands.runBridgeCommand("加载包依赖", () => requestPackageDependencyGraph(packageName ?? null, options), {
      successFeedback: {
        level: "INFO",
        message: "正在加载包依赖视图。",
      },
    });
  }

  /** 重新加载架构图，可选是否包含外部库与 JDK。 */
  function handleRequestArchitectureGraph(options: { includeExternalLibraries?: boolean; includeJdk?: boolean } = {}) {
    bridgeCommands.runBridgeCommand("加载架构图", () => requestArchitectureGraph(options));
  }

  /** 加载 Review Graph 并附带自定义审查选项（diff 项过滤、严重级别阈值等）。 */
  function handleRequestReviewGraphWithOptions(
    selectedDiffItemIds: string[] = [],
    review: Partial<IndexedReviewGraphOptions>,
  ) {
    bridgeCommands.runBridgeCommand("加载 Review Graph", () => requestReviewGraph(selectedDiffItemIds, { review }));
  }

  /** 触发当前图的 Mermaid 文本导出。 */
  function handleExportMermaid() {
    bridgeCommands.runBridgeCommand("导出 Mermaid", () => exportMermaid(), {
      successFeedback: {
        level: "INFO",
        message: "已请求导出 Mermaid。",
      },
    });
  }

  /** 进入 diff 对比模式，展示草稿相对原文件的差异。 */
  function handleShowDiffMode() {
    bridgeCommands.runBridgeCommand("代码对比", () => showDiffMode());
  }

  /** 请求重新同步草稿预览，确保视图与后端最新状态一致。 */
  function handleRequestSyncPreview() {
    bridgeCommands.runBridgeCommand("同步预览", () => requestSyncPreview());
  }

  /** 异步生成代码草稿（代码 diff），不阻塞前端交互。 */
  function handleRequestCodeDrafts() {
    bridgeCommands.submitAsyncBridgeCommand("代码草稿", () => requestCodeDraftsAsync(), {
      successFeedback: {
        level: "INFO",
        message: "已请求生成代码 diff。",
      },
    });
  }

  /** 打开 IDE 的设置面板（跳转到本插件相关页）。 */
  function handleOpenSettings() {
    bridgeCommands.runBridgeCommand("打开设置", () => requestOpenSettings(), {
      successFeedback: {
        level: "INFO",
        message: "正在打开 IDE 设置...",
      },
    });
  }

  /** 将当前草稿实际写入文件系统，落地变更。 */
  function handleWriteDrafts() {
    bridgeCommands.runBridgeCommand("写入代码 diff", () => applyCodeDrafts());
  }

  /** 在编辑器中打开指定路径的草稿文件。 */
  function handleOpenDraft(targetPath: string) {
    bridgeCommands.runBridgeCommand("打开草稿文件", () => requestDraftNavigation(targetPath));
  }

  /** 当节点因容量限制被折叠时，请求展开其隐藏的链路分支。 */
  function handleExpandOverflowNode(nodeId: string, nodeTitle: string) {
    bridgeCommands.runBridgeCommand("展开链路分支", () => requestExpandOverflowNode(nodeId), {
      successFeedback: {
        level: "INFO",
        message: `已请求继续展开链路：${nodeTitle}`,
      },
    });
  }

  /** 展开指定节点对应的方法调用，把被调方法接入图谱。 */
  function handleExpandInvocation(nodeId: string) {
    bridgeCommands.runBridgeCommand("展开调用方法", () => requestExpandInvocation(nodeId), {
      successFeedback: {
        level: "INFO",
        message: "已请求展开被调方法。",
      },
    });
  }

  /** 移除之前生成的调用展开内容，回收画布空间。 */
  function handleRemoveInvocationExpansion(expansionId: string) {
    bridgeCommands.runBridgeCommand("移除调用展开", () => requestRemoveInvocationExpansion(expansionId), {
      successFeedback: {
        level: "INFO",
        message: "已请求移除调用展开内容。",
      },
    });
  }

  return {
    handleRequestAnalysisDisplayMode,
    handleRequestArchitectureGraph,
    handleRequestClassDiagram,
    handleRequestClassDiagramWithOptions,
    handleRequestClassUsages,
    handleRequestPackageDependencyGraph,
    handleRequestReviewGraphWithOptions,
    handleExportMermaid,
    handleShowDiffMode,
    handleRequestSyncPreview,
    handleRequestCodeDrafts,
    handleOpenSettings,
    handleWriteDrafts,
    handleOpenDraft,
    handleExpandOverflowNode,
    handleExpandInvocation,
    handleRemoveInvocationExpansion,
  };
}

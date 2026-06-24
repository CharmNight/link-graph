import { canEditProjectedNodeLayout } from "./graphProjectionPermissions";
import type { AnalysisDisplayMode, GraphProjectionIndex, LinkGraphNode } from "./types";

/**
 * 判定单个节点在当前上下文下是否允许用户手动调整布局（位置/尺寸等）。
 *
 * 判定优先级：
 * 1) 若传入投影索引，则交给投影层判断（投影视图可能有自己的锁，例如只读视图）；
 * 2) 流程图、架构图、类图、审查图这几个视图默认允许编辑，因为它们本就是给人手工整理的；
 * 3) 元数据 linkGraph.manual=true 表示该节点是用户手动添加/调整过的，自然允许继续编辑；
 * 4) 最后按节点来源标签（sourceTag）判定：基线设计、人工草稿、AI 草稿来源的节点都可编辑，
 *    其余来源（例如来自索引或导入）默认不可编辑，避免误改原始数据。
 */
export function canEditNodeLayout(
  node: LinkGraphNode,
  analysisDisplayMode?: AnalysisDisplayMode,
  projectionIndex?: GraphProjectionIndex | null,
): boolean {
  // 投影索引存在时以投影策略为准，统一走投影层的判定函数
  if (projectionIndex) {
    return canEditProjectedNodeLayout(projectionIndex, node);
  }
  // 流程图视图天然需要可编辑，节点位置是流程语义的一部分
  if (analysisDisplayMode === "FLOWCHART") {
    return true;
  }
  // 架构图、类图、审查图作为可视化思考工具，同样允许手工调整
  if (analysisDisplayMode === "ARCHITECTURE_GRAPH" || analysisDisplayMode === "CLASS_DIAGRAM" || analysisDisplayMode === "REVIEW_GRAPH") {
    return true;
  }
  // 元数据显式标记为手动来源的节点允许编辑
  if (node.metadata?.["linkGraph.manual"] === "true") {
    return true;
  }
  // 按节点来源标签做最后兜底
  switch (node.sourceTag) {
    case "DESIGN_BASELINE":
    case "DRAFT_MANUAL":
    case "DRAFT_AI":
      return true;
    default:
      return false;
  }
}

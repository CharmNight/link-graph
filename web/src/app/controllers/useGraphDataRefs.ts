import { useEffect, useRef, type MutableRefObject } from "react";

import type { AnalysisDisplayMode, LinkGraphDocument, LinkGraphEdge, LinkGraphNode } from "../types";

/**
 * 图谱相关数据的 ref 集合，便于在回调中按需读取最新值（不触发重渲染）。
 *
 * m7 抽出自 App.tsx：原文件 5 个 useRef + 5 个 useEffect 同步块占 ~30 行，
 * 集中到本 hook 让 ref 与 sync 语义对齐，调用方只用一份 ref 对象。
 *
 * 注意：本 hook 不替代「按需读最新值」语义；如果调用方依赖 prop 变化触发重渲染，
 * 应直接用 prop 而非 ref。
 */
export interface GraphDataRefs {
  nodesRef: MutableRefObject<LinkGraphNode[]>;
  edgesRef: MutableRefObject<LinkGraphEdge[]>;
  draftGraphRef: MutableRefObject<LinkGraphDocument | null>;
  anchorNodeIdRef: MutableRefObject<string | null>;
  analysisDisplayModeRef: MutableRefObject<AnalysisDisplayMode>;
}

/** 把图谱数据初始化到 ref，并在数据变化时同步；返回 ref 对象集合。 */
export function useGraphDataRefs(
  nodes: LinkGraphNode[],
  edges: LinkGraphEdge[],
  draftGraph: LinkGraphDocument | null,
  anchorNodeId: string | null,
  analysisDisplayMode: AnalysisDisplayMode,
): GraphDataRefs {
  const nodesRef = useRef(nodes);
  const edgesRef = useRef(edges);
  const draftGraphRef = useRef(draftGraph);
  const anchorNodeIdRef = useRef(anchorNodeId);
  const analysisDisplayModeRef = useRef(analysisDisplayMode);

  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);
  useEffect(() => {
    edgesRef.current = edges;
  }, [edges]);
  useEffect(() => {
    draftGraphRef.current = draftGraph;
  }, [draftGraph]);
  useEffect(() => {
    anchorNodeIdRef.current = anchorNodeId;
  }, [anchorNodeId]);
  useEffect(() => {
    analysisDisplayModeRef.current = analysisDisplayMode;
  }, [analysisDisplayMode]);

  return {
    nodesRef,
    edgesRef,
    draftGraphRef,
    anchorNodeIdRef,
    analysisDisplayModeRef,
  };
}

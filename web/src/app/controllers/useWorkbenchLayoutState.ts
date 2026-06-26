import { useEffect, useState } from "react";
import {
  clampAssistantWorkbenchWidth,
  readHybridWorkbenchLayoutPreference,
  writeHybridWorkbenchLayoutPreference,
} from "../appWorkbenchPreferences";

/**
 * 工作台布局状态控制器（P2-1 前端架构拆分）。
 *
 * 抽取自 App.tsx，封装与工作台布局相关的本地状态与持久化副作用：
 * - hybridLayoutPreference：折叠状态、宽度等布局偏好
 * - outlineQuery：大纲搜索关键词
 * - handleOutlineCollapsedChange / handleWorkbenchWidthChange：偏好更新
 * - 持久化副作用：偏好变化时写回本地存储
 */
export function useWorkbenchLayoutState() {
  const [hybridLayoutPreference, setHybridLayoutPreference] = useState(readHybridWorkbenchLayoutPreference);
  const [outlineQuery, setOutlineQuery] = useState("");

  useEffect(() => {
    writeHybridWorkbenchLayoutPreference(hybridLayoutPreference);
  }, [hybridLayoutPreference]);

  /** 用户切换大纲折叠状态时持久化布局偏好 */
  function handleOutlineCollapsedChange(outlineCollapsed: boolean) {
    setHybridLayoutPreference((current) => ({
      ...current,
      outlineCollapsed,
    }));
  }

  /** 用户拖动调整工作台宽度，钳制到合法区间后持久化 */
  function handleWorkbenchWidthChange(workbenchWidth: number) {
    setHybridLayoutPreference((current) => ({
      ...current,
      workbenchWidth: clampAssistantWorkbenchWidth(workbenchWidth),
    }));
  }

  return {
    hybridLayoutPreference,
    setHybridLayoutPreference,
    outlineQuery,
    setOutlineQuery,
    handleOutlineCollapsedChange,
    handleWorkbenchWidthChange,
  };
}

import { useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { GraphContextMenuAction } from "./actionSchema";

/** GraphContextMenu 组件的入参。 */
interface GraphContextMenuProps {
  /** 菜单弹出位置 x（客户端坐标）。 */
  x: number;
  /** 菜单弹出位置 y（客户端坐标）。 */
  y: number;
  /** 菜单项列表。 */
  actions: GraphContextMenuAction[];
}

/** 菜单与视口边缘的安全间距。 */
const VIEWPORT_MARGIN = 12;
/** 菜单宽度兜底值（DOM 未测量时使用）。 */
const FALLBACK_MENU_WIDTH = 220;
/** 单个菜单项高度兜底值。 */
const FALLBACK_MENU_ITEM_HEIGHT = 38;
/** 菜单额外 chrome（padding/border 等）高度兜底值。 */
const FALLBACK_MENU_CHROME_HEIGHT = 16;

/**
 * 把菜单坐标夹紧到视口内。
 * 确保菜单左/上 >= 安全边距，且右/下 <= 视口宽/高 - 菜单尺寸 - 安全边距。
 */
function clampMenuCoordinate(value: number, size: number, viewportSize: number): number {
  const maxValue = Math.max(VIEWPORT_MARGIN, viewportSize - size - VIEWPORT_MARGIN);
  return Math.max(VIEWPORT_MARGIN, Math.min(value, maxValue));
}

/**
 * 画布右键菜单组件。
 *
 * 使用 Portal 渲染到 document.body，避免被父容器的 overflow/transform 裁切。
 *
 * 位置计算：
 * - 初次渲染用兜底尺寸计算（基于 actions.length 估算高度）；
 * - useLayoutEffect 在 DOM 挂载后用 getBoundingClientRect 取真实尺寸；
 * - 真实尺寸到位后重新计算位置，保证不超出视口。
 */
export function GraphContextMenu({
  x,
  y,
  actions,
}: GraphContextMenuProps) {
  const menuRef = useRef<HTMLDivElement | null>(null);
  // 初始用兜底尺寸
  const [menuSize, setMenuSize] = useState(() => ({
    width: FALLBACK_MENU_WIDTH,
    height: Math.max(FALLBACK_MENU_CHROME_HEIGHT, (actions.length * FALLBACK_MENU_ITEM_HEIGHT) + FALLBACK_MENU_CHROME_HEIGHT),
  }));

  // 测量真实尺寸并更新
  useLayoutEffect(() => {
    const menuNode = menuRef.current;
    if (!menuNode) {
      return;
    }
    const rect = menuNode.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) {
      return;
    }
    setMenuSize((current) => {
      const nextWidth = Math.round(rect.width);
      const nextHeight = Math.round(rect.height);
      // 尺寸未变时跳过更新避免无限循环
      if (current.width === nextWidth && current.height === nextHeight) {
        return current;
      }
      return { width: nextWidth, height: nextHeight };
    });
  }, [actions.length]);

  // 用夹紧后的坐标定位菜单
  const position = useMemo(() => ({
    left: clampMenuCoordinate(x, menuSize.width, window.innerWidth),
    top: clampMenuCoordinate(y, menuSize.height, window.innerHeight),
  }), [menuSize.height, menuSize.width, x, y]);

  const menu = (
    <div
      ref={menuRef}
      role="menu"
      className="canvas-context-menu"
      style={{
        left: position.left,
        top: position.top,
      }}
    >
      {actions.map((action) => (
        <button
          key={action.id}
          type="button"
          role="menuitem"
          onClick={action.onSelect}
        >
          {action.label}
        </button>
      ))}
    </div>
  );

  // 使用 Portal 渲染到 document.body 避免裁切；SSR（无 document）时直接渲染
  return typeof document === "undefined" ? menu : createPortal(menu, document.body);
}

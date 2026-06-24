// 图画布右键菜单的呈现模型。
// 主要解决两个问题：
// 1) 菜单弹出位置不能超出视口边界（避免被遮挡）；
// 2) 把鼠标在画布上的像素坐标转换为图坐标（用于"在此处添加节点"等操作）。
import type { GraphPosition } from "../types";

/** 菜单与视口边缘的安全间距（像素）。 */
const CONTEXT_MENU_SAFE_MARGIN = 16;
/** 菜单预估宽度；用于判断是否会超出右边界。 */
const CONTEXT_MENU_ESTIMATED_WIDTH = 240;
/** 菜单预估高度；用于判断是否会超出下边界。 */
const CONTEXT_MENU_ESTIMATED_HEIGHT = 360;

/** 视口尺寸信息（用于菜单定位）。 */
export interface ContextMenuViewport {
  /** 视口宽度（像素）。 */
  width: number;
  /** 视口高度（像素）。 */
  height: number;
}

/** 画布位置信息（用于把客户端坐标转换为画布坐标）。 */
export interface ContextMenuRect {
  /** 画布左上角的客户端 x 坐标。 */
  left: number;
  /** 画布左上角的客户端 y 坐标。 */
  top: number;
}

/**
 * 画布右键菜单的联合状态。
 *
 * 三种触发场景：
 * - pane：在画布空白处右键（可携带画布坐标用于"在此添加节点"）；
 * - node：在节点上右键；
 * - edge：在边上右键。
 */
export type GraphFlowContextMenuState =
  | {
      kind: "pane";
      /** 客户端 x 坐标。 */
      x: number;
      /** 客户端 y 坐标。 */
      y: number;
      /** 对应的画布坐标；可空（未转换时）。 */
      position?: GraphPosition;
    }
  | {
      kind: "node";
      x: number;
      y: number;
      /** 触发菜单的节点 ID。 */
      nodeId: string;
    }
  | {
      kind: "edge";
      x: number;
      y: number;
      /** 触发菜单的边 ID。 */
      edgeId: string;
    };

/**
 * 解析菜单弹出位置：把鼠标位置约束在视口内，避免菜单超出边界被裁切。
 *
 * 计算方式：x/y 各自做"夹紧"——确保菜单左上角 >= 安全边距，
 * 同时菜单右下角不超过视口宽高。
 *
 * @param x 鼠标客户端 x 坐标
 * @param y 鼠标客户端 y 坐标
 * @param viewport 视口尺寸；为空时直接返回原始坐标（调用方自行处理）
 */
export function resolveContextMenuPoint(
  x: number,
  y: number,
  viewport: ContextMenuViewport | null | undefined,
): { x: number; y: number } {
  if (!viewport) {
    return { x, y };
  }
  return {
    // x：左夹紧到安全边距，右夹紧到"视口宽度 - 菜单宽度 - 安全边距"
    x: Math.min(
      Math.max(CONTEXT_MENU_SAFE_MARGIN, x),
      Math.max(
        CONTEXT_MENU_SAFE_MARGIN,
        viewport.width - CONTEXT_MENU_ESTIMATED_WIDTH - CONTEXT_MENU_SAFE_MARGIN,
      ),
    ),
    y: Math.min(
      Math.max(CONTEXT_MENU_SAFE_MARGIN, y),
      Math.max(
        CONTEXT_MENU_SAFE_MARGIN,
        viewport.height - CONTEXT_MENU_ESTIMATED_HEIGHT - CONTEXT_MENU_SAFE_MARGIN,
      ),
    ),
  };
}

/**
 * 把客户端坐标转换为画布坐标。
 *
 * @param clientX 鼠标客户端 x
 * @param clientY 鼠标客户端 y
 * @param rect 画布的位置矩形；为空时返回 undefined（无法转换）
 * @returns 画布坐标（左上角为原点，不允许负值）
 */
export function resolvePanePositionFromRect(
  clientX: number,
  clientY: number,
  rect: ContextMenuRect | null | undefined,
): GraphPosition | undefined {
  if (!rect) {
    return undefined;
  }
  // 减去画布左上角坐标得到画布内坐标；负值钳为 0（鼠标不在画布内时）
  return {
    x: Math.max(0, clientX - rect.left),
    y: Math.max(0, clientY - rect.top),
  };
}

import type { SVGProps } from "react";

/**
 * SVG 图标集。项目此前没有共享图标封装，
 * 这里建立约定：所有图标统一 stroke="currentColor"、尺寸由 CSS 控制，
 * 不依赖任何图标库，保持单文件可维护。
 *
 * 新增图标：在 ICONS 里加一个 path 即可。
 */

export type IconName =
  | "import"
  | "export"
  | "compare"
  | "sync"
  | "settings"
  | "play"
  | "search"
  | "focus"
  | "layout"
  | "zoomIn"
  | "zoomOut"
  | "expand"
  | "close"
  | "source"
  | "edit"
  | "ask"
  | "trash";

const ICONS: Record<IconName, string> = {
  // 导入：向下箭头入框
  import: "M12 3v12m0 0l-4-4m4 4l4-4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2",
  // 导出：向上箭头出框
  export: "M12 15V3m0 0l-4 4m4-4l4 4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2",
  // 对比：上下箭头分列
  compare: "M12 3v18M3 7l4-4 4 4M21 17l-4 4-4-4",
  // 同步：循环箭头
  sync: "M21 12a9 9 0 11-3-6.7L21 8M21 3v5h-5",
  // 设置：齿轮
  settings: "M12 15a3 3 0 100-6 3 3 0 000 6z M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 11-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 11-4 0v-.09a1.65 1.65 0 00-1-1.51 1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 11-2.83-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 110-4h.09a1.65 1.65 0 001.51-1 1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 112.83-2.83l.06.06a1.65 1.65 0 001.82.33h0a1.65 1.65 0 001-1.51V3a2 2 0 114 0v.09a1.65 1.65 0 001 1.51h0a1.65 1.65 0 001.82-.33l.06-.06a2 2 0 112.83 2.83l-.06.06a1.65 1.65 0 00-.33 1.82v0a1.65 1.65 0 001.51 1H21a2 2 0 110 4h-.09a1.65 1.65 0 00-1.51 1z",
  // 下一步：右三角
  play: "M5 3l14 9-14 9V3z",
  // 搜索
  search: "M11 17a7 7 0 100-14 7 7 0 000 14z M21 21l-4.3-4.3",
  // 居中聚焦
  focus: "M12 8a4 4 0 100 8 4 4 0 000-8z M12 2v3M12 19v3M2 12h3M19 12h3",
  // 布局网格
  layout: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  // 放大
  zoomIn: "M11 17a7 7 0 100-14 7 7 0 000 14z M21 21l-4.3-4.3M11 8v6M8 11h6",
  // 缩小
  zoomOut: "M11 17a7 7 0 100-14 7 7 0 000 14z M21 21l-4.3-4.3M8 11h6",
  // 展开
  expand: "M8 3H5a2 2 0 00-2 2v3M16 3h3a2 2 0 012 2v3M8 21H5a2 2 0 01-2-2v-3M16 21h3a2 2 0 002-2v-3",
  // 关闭
  close: "M18 6L6 18M6 6l12 12",
  // 源码定位
  source: "M16 18l6-6-6-6M8 6l-6 6 6 6",
  // 编辑
  edit: "M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7 M18.5 2.5a2.12 2.12 0 013 3L12 15l-4 1 1-4 9.5-9.5z",
  // 提问
  ask: "M12 22a10 10 0 100-20 10 10 0 000 20z M9.09 9a3 3 0 015.83 1c0 2-3 3-3 3M12 17h.01",
  // 删除
  trash: "M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2",
};

interface IconProps extends Omit<SVGProps<SVGSVGElement>, "name"> {
  name: IconName;
}

export function Icon({ name, ...rest }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="icon"
      {...rest}
    >
      <path d={ICONS[name]} />
    </svg>
  );
}

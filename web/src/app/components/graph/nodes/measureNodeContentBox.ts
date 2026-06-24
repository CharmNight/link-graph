// 节点内容区域尺寸测量工具。
// 用多种浏览器 API 尝试获取元素尺寸，避免单一 API 在特定环境下失效。

/** 测量结果：内容区域的宽 × 高。 */
interface NodeContentBox {
  width: number;
  height: number;
}

/**
 * 校验宽高是否为有效正数；无效时返回 null。
 * 处理 NaN、Infinity、零/负数等情况。
 */
function validBox(width: number, height: number): NodeContentBox | null {
  if (!Number.isFinite(width) || !Number.isFinite(height)) {
    return null;
  }
  if (width <= 0 || height <= 0) {
    return null;
  }
  return { width, height };
}

/**
 * 测量元素的内容区域尺寸。
 *
 * 按优先级依次尝试三种浏览器 API：
 * 1) offsetWidth/Height（布局尺寸，最快最可靠）；
 * 2) clientWidth/Height（可见区域，排除滚动条）；
 * 3) getBoundingClientRect（最终渲染尺寸，精度最高但性能略差）。
 *
 * 任一 API 返回有效值即停止；都无效时返回 null。
 *
 * @param element 待测量的 DOM 元素
 * @return 测量结果；元素为 null 或无法测量时返回 null
 */
export function measureNodeContentBox(element: HTMLElement | null): NodeContentBox | null {
  if (!element) {
    return null;
  }
  // 第一步：offsetWidth/Height（最快）
  const offsetBox = validBox(element.offsetWidth, element.offsetHeight);
  if (offsetBox) {
    return offsetBox;
  }
  // 第二步：clientWidth/Height
  const clientBox = validBox(element.clientWidth, element.clientHeight);
  if (clientBox) {
    return clientBox;
  }
  // 第三步：getBoundingClientRect（精度最高但性能略差）
  const rect = element.getBoundingClientRect();
  return validBox(rect.width, rect.height);
}

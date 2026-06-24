/**
 * 人工创建节点的种类。
 * - METHOD：表示用户在设计图上手添加的方法节点
 * - DOC_PAGE：表示用户在设计图上手添加的文档/笔记页
 */
type ManualNodeKind = "METHOD" | "DOC_PAGE";

/** 方法类人工节点 ID 的格式：design:<序号>。 */
const METHOD_ID_PATTERN = /^design:(\d+)$/;
/** 文档页类人工节点 ID 的格式：design-note:<序号>。 */
const DOC_PAGE_ID_PATTERN = /^design-note:(\d+)$/;

/**
 * 扫描节点列表中匹配指定 ID 模式的最大序号。
 * 用于在分配新 ID 时避免与已有 ID 冲突。
 * @param nodes 待扫描的节点集合
 * @param pattern 含一个捕获组的正则，捕获组应匹配数字序号
 * @returns 已存在的最大序号；若没有任何匹配，返回 0。
 */
function maxSuffix(nodes: Array<{ id: string }>, pattern: RegExp): number {
  return nodes.reduce((maxValue, node) => {
    const matched = pattern.exec(node.id);
    if (!matched) {
      return maxValue;
    }
    const nextValue = Number(matched[1]);
    // 解析失败或非有限数（NaN/Infinity）时忽略，避免污染最大值
    return Number.isFinite(nextValue) ? Math.max(maxValue, nextValue) : maxValue;
  }, 0);
}

/**
 * 计算下一个可用的序号。综合考虑：
 * 1) 节点列表长度（防止节点 ID 都不带序号导致冲突）；
 * 2) 方法 ID 的最大已用序号；
 * 3) 文档页 ID 的最大已用序号。
 * 三者最大值 +1 作为新序号，保证全局唯一。
 */
export function nextManualNodeSequence(
  nodes: Array<{ id: string }>,
): number {
  return (
    Math.max(
      nodes.length,
      maxSuffix(nodes, METHOD_ID_PATTERN),
      maxSuffix(nodes, DOC_PAGE_ID_PATTERN),
    ) + 1
  );
}

/**
 * 创建一个人工节点 ID 分配器。基于已有节点集合初始化起点序号，
 * 每次调用返回的函数都会给出并自增序号，按照 kind 拼接对应前缀。
 * @param nodes 当前已存在的节点集合，用于推断起点序号
 * @returns 接收 kind 返回唯一 ID 的闭包
 */
export function createManualNodeIdAllocator(
  nodes: Array<{ id: string }>,
): (kind: ManualNodeKind) => string {
  // 闭包内持有的"下一个序号"，每次分配后自增
  let nextId = nextManualNodeSequence(nodes);

  return (kind: ManualNodeKind) => {
    if (kind === "DOC_PAGE") {
      // 文档页使用 design-note: 前缀，与方法节点区分
      return `design-note:${nextId++}`;
    }
    // 默认走方法节点前缀
    return `design:${nextId++}`;
  };
}

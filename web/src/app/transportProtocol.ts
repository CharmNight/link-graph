// 引入前后端共享的传输协议契约 JSON。该 JSON 由协议目录统一维护，
// 用于保证 Kotlin 后端与 TS 前端对传输类型枚举与版本号的理解一致。
import rawTransportContract from "../../../protocol/graph-editor-transport-contract.json";

/**
 * 协议契约对象的内部投影。直接读取 JSON 字段并断言其结构，
 * schemaVersion 标识整体协议版本（当前为 2），incrementalTransportTypes
 * 列出所有增量传输消息的类型字符串。
 */
const transportContract = rawTransportContract as unknown as {
  readonly schemaVersion: 2;
  readonly incrementalTransportTypes: readonly ["ARTIFACT_SLICE", "FEEDBACK_SLICE"];
};

/** 协议 schema 的当前版本号，用于握手时校验前后端兼容性。 */
export const LINK_GRAPH_TRANSPORT_SCHEMA_VERSION = transportContract.schemaVersion;

/** 增量传输所支持的全部消息类型枚举，作为运行时的类型守卫来源。 */
export const LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES = transportContract.incrementalTransportTypes;

/** 单个增量传输消息的类型别名，从枚举数组派生以避免手写不同步。 */
export type LinkGraphIncrementalTransportType =
  (typeof LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES)[number];

/** 产物切片（Artifact）增量消息的固定类型字符串，用于消息分发时的辨别。 */
export const LINK_GRAPH_ARTIFACT_SLICE_TRANSPORT_TYPE = LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES[0];

/** 反馈切片（Feedback）增量消息的固定类型字符串，用于消息分发时的辨别。 */
export const LINK_GRAPH_FEEDBACK_SLICE_TRANSPORT_TYPE = LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES[1];

/**
 * 类型守卫：判断任意值是否为协议定义的合法增量传输类型字符串。
 * 用于解析来自桥接层的弱类型消息时收窄类型，避免非法字符串进入后续分发逻辑。
 */
export function isLinkGraphIncrementalTransportType(
  value: unknown,
): value is LinkGraphIncrementalTransportType {
  return typeof value === "string"
    && LINK_GRAPH_INCREMENTAL_TRANSPORT_TYPES.includes(value as LinkGraphIncrementalTransportType);
}

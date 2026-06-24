import type { LinkGraphIncrementalTransportType } from "./transportProtocol";
import type { LinkGraphBootstrapState } from "./types";

/**
 * 全量快照信封：携带一份完整的引导态（Bootstrap State），
 * 用于会话首次握手或状态重置。revision 单调递增，便于接收方判断新旧。
 */
export interface LinkGraphSnapshotEnvelope {
  /** 会话标识，用于区分不同图谱会话的传输流。 */
  sessionId: string;
  /** 本会话内消息的递增版本号，用于丢序检测。 */
  revision: number;
  /** 序列化后的完整引导态。 */
  state: LinkGraphBootstrapState;
  /** 可选的增量类型标记；快照通常不带，仅用于复用同一信封结构。 */
  transportType?: LinkGraphIncrementalTransportType;
}

/** 所有增量信封共享的基础字段，标识会话与版本。 */
export interface LinkGraphTransportEnvelopeBase {
  sessionId: string;
  revision: number;
}

/**
 * 产物切片信封：携带引导态的部分字段（增量产物），用于在已建立基线后推送小幅更新。
 * type 字段固定为 ARTIFACT_SLICE，便于分发器按消息类型路由。
 */
export interface LinkGraphArtifactSliceEnvelope extends LinkGraphTransportEnvelopeBase {
  type: LinkGraphIncrementalTransportType & "ARTIFACT_SLICE";
  /** 增量产物字段集合，缺失字段表示本次不更新。 */
  state: Partial<LinkGraphBootstrapState>;
}

/**
 * 反馈切片信封：携带来自后端的反馈/审核结果增量。
 * type 字段固定为 FEEDBACK_SLICE，与产物切片在分发层做区分。
 */
export interface LinkGraphFeedbackSliceEnvelope extends LinkGraphTransportEnvelopeBase {
  type: LinkGraphIncrementalTransportType & "FEEDBACK_SLICE";
  /** 增量反馈字段集合，缺失字段表示本次不更新。 */
  state: Partial<LinkGraphBootstrapState>;
}

/** 增量传输信封的联合类型：按 type 字段区分产物切片或反馈切片。 */
export type LinkGraphIncrementalTransportEnvelope =
  | LinkGraphArtifactSliceEnvelope
  | LinkGraphFeedbackSliceEnvelope;

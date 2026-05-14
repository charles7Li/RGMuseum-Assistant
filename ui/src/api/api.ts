import { get, post, patch, put, del, BASE_URL } from "./http.ts";
import type { ChatMessageVO, MessageType } from "../types";

// 类型定义
export interface ChatOptions {
  temperature?: number;
  topP?: number;
  messageLength?: number;
}

export type ModelType = "deepseek-chat" | "glm-4.6" | "qwen3.6-plus";

export interface CreateAgentRequest {
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
}

export interface UpdateAgentRequest {
  name?: string;
  description?: string;
  systemPrompt?: string;
  model?: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
}

export interface CreateAgentResponse {
  agentId: string;
}

export interface AgentVO {
  id: string;
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
  createdAt?: string;
  updatedAt?: string;
}

export interface GetAgentsResponse {
  agents: AgentVO[];
}

/**
 * 获取所有 agents
 */
export async function getAgents(): Promise<GetAgentsResponse> {
  return get<GetAgentsResponse>("/agents");
}

/**
 * 创建 agent
 */
export async function createAgent(
  request: CreateAgentRequest,
): Promise<CreateAgentResponse> {
  return post<CreateAgentResponse>("/agents", request);
}

/**
 * 删除 agent
 */
export async function deleteAgent(agentId: string): Promise<void> {
  return del<void>(`/agents/${agentId}`);
}

/**
 * 更新 agent
 */
export async function updateAgent(
  agentId: string,
  request: UpdateAgentRequest,
): Promise<void> {
  return patch<void>(`/agents/${agentId}`, request);
}

/**
 * 创建聊天会话
 */
export interface CreateChatSessionRequest {
  agentId: string;
  title?: string;
}

export interface CreateChatSessionResponse {
  chatSessionId: string;
}

export async function createChatSession(
  request: CreateChatSessionRequest,
): Promise<CreateChatSessionResponse> {
  return post<CreateChatSessionResponse>("/chat-sessions", request);
}

/**
 * 聊天会话相关类型和接口
 */
export interface ChatSessionVO {
  id: string;
  agentId: string;
  title?: string;
}

export interface GetChatSessionsResponse {
  chatSessions: ChatSessionVO[];
}

export interface GetChatSessionResponse {
  chatSession: ChatSessionVO;
}

export interface UpdateChatSessionRequest {
  title?: string;
}

/**
 * 获取所有聊天会话
 */
export async function getChatSessions(): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>("/chat-sessions");
}

/**
 * 获取单个聊天会话
 */
export async function getChatSession(
  chatSessionId: string,
): Promise<GetChatSessionResponse> {
  return get<GetChatSessionResponse>(`/chat-sessions/${chatSessionId}`);
}

/**
 * 根据 agentId 获取聊天会话
 */
export async function getChatSessionsByAgentId(
  agentId: string,
): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>(`/chat-sessions/agent/${agentId}`);
}

/**
 * 更新聊天会话
 */
export async function updateChatSession(
  chatSessionId: string,
  request: UpdateChatSessionRequest,
): Promise<void> {
  return patch<void>(`/chat-sessions/${chatSessionId}`, request);
}

/**
 * 删除聊天会话
 */
export async function deleteChatSession(chatSessionId: string): Promise<void> {
  return del<void>(`/chat-sessions/${chatSessionId}`);
}

/**
 * 聊天消息相关类型和接口
 */
export interface MetaData {
  [key: string]: unknown;
}

export interface GetChatMessagesResponse {
  chatMessages: ChatMessageVO[];
}

export interface CreateChatMessageRequest {
  agentId: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: MetaData;
}

export interface CreateChatMessageResponse {
  chatMessageId: string;
}

export interface UpdateChatMessageRequest {
  content?: string;
  metadata?: MetaData;
}

/**
 * 根据 sessionId 获取聊天消息
 */
export async function getChatMessagesBySessionId(
  sessionId: string,
): Promise<GetChatMessagesResponse> {
  return get<GetChatMessagesResponse>(`/chat-messages/session/${sessionId}`);
}

/**
 * 创建聊天消息
 */
export async function createChatMessage(
  request: CreateChatMessageRequest,
): Promise<CreateChatMessageResponse> {
  return post<CreateChatMessageResponse>("/chat-messages", request);
}

/**
 * 更新聊天消息
 */
export async function updateChatMessage(
  chatMessageId: string,
  request: UpdateChatMessageRequest,
): Promise<void> {
  return patch<void>(`/chat-messages/${chatMessageId}`, request);
}

/**
 * 删除聊天消息
 */
export async function deleteChatMessage(chatMessageId: string): Promise<void> {
  return del<void>(`/chat-messages/${chatMessageId}`);
}

/**
 * 知识库相关类型和接口
 */
export interface KnowledgeBaseVO {
  id: string;
  name: string;
  description?: string;
  embeddingRule?: EmbeddingRule;
}

export type EmbeddingRule =
  | "title-only"
  | "title+content(500)"
  | "content-only(500)";

export interface CreateKnowledgeBaseRequest {
  name: string;
  description?: string;
  embeddingRule?: EmbeddingRule;
}

export interface UpdateKnowledgeBaseRequest {
  name?: string;
  description?: string;
  embeddingRule?: EmbeddingRule;
}

export interface GetKnowledgeBasesResponse {
  knowledgeBases: KnowledgeBaseVO[];
}

export interface CreateKnowledgeBaseResponse {
  knowledgeBaseId: string;
}

/**
 * 获取所有知识库
 */
export async function getKnowledgeBases(): Promise<GetKnowledgeBasesResponse> {
  return get<GetKnowledgeBasesResponse>("/knowledge-bases");
}

/**
 * 创建知识库
 */
export async function createKnowledgeBase(
  request: CreateKnowledgeBaseRequest,
): Promise<CreateKnowledgeBaseResponse> {
  return post<CreateKnowledgeBaseResponse>("/knowledge-bases", request);
}

/**
 * 删除知识库
 */
export async function deleteKnowledgeBase(
  knowledgeBaseId: string,
): Promise<void> {
  return del<void>(`/knowledge-bases/${knowledgeBaseId}`);
}

/**
 * 更新知识库
 */
export async function updateKnowledgeBase(
  knowledgeBaseId: string,
  request: UpdateKnowledgeBaseRequest,
): Promise<void> {
  return patch<void>(`/knowledge-bases/${knowledgeBaseId}`, request);
}

/**
 * 文档相关类型和接口
 */
export interface DocumentVO {
  id: string;
  kbId: string;
  filename: string;
  filetype: string;
  size: number;
}

export interface GetDocumentsResponse {
  documents: DocumentVO[];
}

export interface CreateDocumentResponse {
  documentId: string;
}

/**
 * 根据知识库 ID 获取文档列表
 */
export async function getDocumentsByKbId(
  kbId: string,
): Promise<GetDocumentsResponse> {
  return get<GetDocumentsResponse>(`/documents/kb/${kbId}`);
}

/**
 * 上传文档
 */
export async function uploadDocument(
  kbId: string,
  file: File,
  embeddingRule?: EmbeddingRule,
): Promise<CreateDocumentResponse> {
  const formData = new FormData();
  formData.append("kbId", kbId);
  formData.append("file", file);
  if (embeddingRule) {
    formData.append("embeddingRule", embeddingRule);
  }

  const response = await fetch(`${BASE_URL}/documents/upload`, {
    method: "POST",
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`);
  }

  const apiResponse = await response.json();
  if (apiResponse.code !== 200) {
    throw new Error(apiResponse.message || "上传失败");
  }

  return apiResponse.data;
}

/**
 * 删除文档
 */
export async function deleteDocument(documentId: string): Promise<void> {
  return del<void>(`/documents/${documentId}`);
}

/**
 * 工具相关类型和接口
 */
export type ToolType = "FIXED" | "OPTIONAL";

export interface ToolVO {
  name: string;
  description: string;
  type: ToolType;
}

export interface GetOptionalToolsResponse {
  tools: ToolVO[];
}

/**
 * 获取可选工具列表
 */
export async function getOptionalTools(): Promise<GetOptionalToolsResponse> {
  const tools = await get<ToolVO[]>("/tools");
  return { tools };
}

export type RagRuntimeMode = "fast" | "deep";

export interface RagRuntimeSettings {
  mode: RagRuntimeMode;
  rerankEnabled: boolean;
}

export interface UpdateRagRuntimeSettingsRequest {
  mode?: RagRuntimeMode;
  rerankEnabled?: boolean;
}

export async function getRagRuntimeSettings(): Promise<RagRuntimeSettings> {
  return get<RagRuntimeSettings>("/rag/runtime-settings");
}

export async function updateRagRuntimeSettings(
  request: UpdateRagRuntimeSettingsRequest,
): Promise<RagRuntimeSettings> {
  return put<RagRuntimeSettings>("/rag/runtime-settings", request);
}

export type NavigationMode = "walking" | "driving";

export interface NavigationPlanRequest {
  originLongitude: number;
  originLatitude: number;
  destinationLongitude: number;
  destinationLatitude: number;
  destinationName?: string;
  mode?: NavigationMode;
}

export interface NavigationRouteStep {
  index: number;
  instruction: string;
  road?: string;
  distanceMeters: number;
  durationSeconds: number;
  polyline?: string;
}

export interface NavigationPlanResponse {
  provider: string;
  mode: NavigationMode;
  origin: string;
  destination: string;
  distanceMeters: number;
  durationSeconds: number;
  overview: string;
  polyline: string;
  steps: NavigationRouteStep[];
}

export async function planNavigationRoute(
  request: NavigationPlanRequest,
): Promise<NavigationPlanResponse> {
  return post<NavigationPlanResponse>("/navigation/plan", request);
}

export interface BrowserLocation {
  longitude: number;
  latitude: number;
  accuracyMeters?: number;
}

export function getCurrentBrowserLocation(
  timeoutMs = 10000,
): Promise<BrowserLocation> {
  if (!navigator.geolocation) {
    return Promise.reject(new Error("当前浏览器不支持定位"));
  }
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        resolve({
          longitude: position.coords.longitude,
          latitude: position.coords.latitude,
          accuracyMeters: position.coords.accuracy,
        });
      },
      (error) => {
        reject(new Error(error.message || "获取定位失败"));
      },
      {
        enableHighAccuracy: true,
        timeout: timeoutMs,
        maximumAge: 0,
      },
    );
  });
}

export async function planNavigationFromCurrentLocation(params: {
  destinationLongitude: number;
  destinationLatitude: number;
  destinationName?: string;
  mode?: NavigationMode;
}): Promise<NavigationPlanResponse> {
  const location = await getCurrentBrowserLocation();
  return planNavigationRoute({
    originLongitude: location.longitude,
    originLatitude: location.latitude,
    destinationLongitude: params.destinationLongitude,
    destinationLatitude: params.destinationLatitude,
    destinationName: params.destinationName,
    mode: params.mode ?? "walking",
  });
}

export interface MuseumMapPlanRequest {
  originLongitude: number;
  originLatitude: number;
  target: string;
  city?: string;
  mode?: NavigationMode;
  poiLimit?: number;
  routeLimit?: number;
  cityLimit?: boolean;
}

export interface MuseumRouteCandidate {
  poiId: string;
  museumName: string;
  address: string;
  longitude: number;
  latitude: number;
  openTime: string;
  closingTime: string;
  businessStatus: string;
  distanceMeters?: number;
  durationSeconds?: number;
  overview?: string;
  polyline?: string;
  steps?: NavigationRouteStep[];
  routeError?: string;
}

export interface MuseumMapPlanResponse {
  provider: string;
  target: string;
  city: string;
  mode: NavigationMode;
  searchKeywords: string;
  candidateCount: number;
  plannedCount: number;
  candidates: MuseumRouteCandidate[];
  summary: string;
}

export async function planMuseumMapRoute(
  request: MuseumMapPlanRequest,
): Promise<MuseumMapPlanResponse> {
  return post<MuseumMapPlanResponse>("/museum/map/plan", request);
}

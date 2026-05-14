import React, { useCallback, useEffect, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { Button, Modal, Radio, Space, Switch, message as antdMessage } from "antd";
import { SettingOutlined } from "@ant-design/icons";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import {
  createChatMessage,
  createChatSession,
  getChatMessagesBySessionId,
  getChatSession,
  getRagRuntimeSettings,
  updateRagRuntimeSettings,
  type RagRuntimeMode,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const { state } = useLocation();
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [agentId, setAgentId] = useState<string>("");

  const [displayAgentStatus, setDisplayAgentStatus] = useState(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<SseMessageType | undefined>(
    undefined,
  );

  const [streamingContent, setStreamingContent] = useState<string>("");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [runtimeMode, setRuntimeMode] = useState<RagRuntimeMode>("fast");
  const [rerankEnabled, setRerankEnabled] = useState(true);

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => [...prevMessages, message]);
  };

  const getChatMessages = useCallback(async () => {
    if (!chatSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(chatSessionId);
    setMessages(resp.chatMessages);

    const sessionResp = await getChatSession(chatSessionId);
    setAgentId(sessionResp.chatSession.agentId);
  }, [chatSessionId]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages().then();
  }, [chatSessionId, getChatMessages]);

  const handleSendMessage = async (value: string | { text: string }) => {
    const text = typeof value === "string" ? value : value.text;
    if (!text || !text.trim()) return;

    if (!chatSessionId) {
      if (!agentId) {
        antdMessage.warning("请先选择一个智能体助手");
        return;
      }
      setLoading(true);
      try {
        const response = await createChatSession({
          agentId,
          title: text.slice(0, 20),
        });
        await refreshChatSessions();
        navigate(`/chat/${response.chatSessionId}`, {
          replace: true,
          state: {
            init: false,
            initMessage: text,
          },
        });
      } catch (error) {
        console.error("创建聊天会话失败:", error);
        antdMessage.error("创建聊天会话失败，请重试");
      } finally {
        setLoading(false);
      }
      return;
    }

    if (state?.init) {
      await createChatMessage({
        agentId: agentId ?? "",
        sessionId: chatSessionId,
        role: "user",
        content: state.initMessage ?? "",
      });
    } else {
      await createChatMessage({
        agentId: agentId ?? "",
        sessionId: chatSessionId,
        role: "user",
        content: text,
      });
    }
    await getChatMessages();
  };

  const loadRuntimeSettings = useCallback(async () => {
    try {
      const settings = await getRagRuntimeSettings();
      setRuntimeMode(settings.mode);
      setRerankEnabled(settings.rerankEnabled);
    } catch (error) {
      console.error("load runtime settings failed", error);
    }
  }, []);

  useEffect(() => {
    loadRuntimeSettings().then();
  }, [loadRuntimeSettings]);

  const openSettingsModal = async () => {
    await loadRuntimeSettings();
    setSettingsOpen(true);
  };

  const handleSaveSettings = async () => {
    setSettingsLoading(true);
    try {
      const settings = await updateRagRuntimeSettings({
        mode: runtimeMode,
        rerankEnabled,
      });
      setRuntimeMode(settings.mode);
      setRerankEnabled(settings.rerankEnabled);
      setSettingsOpen(false);
      antdMessage.success("运行设置已更新");
    } catch (error) {
      console.error("save runtime settings failed", error);
      antdMessage.error("设置保存失败，请重试");
    } finally {
      setSettingsLoading(false);
    }
  };

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }

    const es = new EventSource(`http://localhost:8080/sse/connect/${chatSessionId}`);
    es.onmessage = (event) => {
      console.log("Received message:", event.data);
    };
    es.onerror = (error) => {
      console.error("SSE error:", error);
    };

    es.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data) as SseMessage;
      if (msg.type === "AI_TOKEN") {
        setStreamingContent((prev) => prev + (msg.payload.token ?? ""));
      } else if (msg.type === "AI_GENERATED_CONTENT") {
        setStreamingContent("");
        addMessage(msg.payload.message);
      } else if (msg.type === "AI_PLANNING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(msg.payload.statusText);
        setAgentStatusType("AI_PLANNING");
      } else if (msg.type === "AI_THINKING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(msg.payload.statusText);
        setAgentStatusType("AI_THINKING");
      } else if (msg.type === "AI_EXECUTING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(msg.payload.statusText);
        setAgentStatusType("AI_EXECUTING");
      } else if (msg.type === "AI_DONE") {
        setDisplayAgentStatus(false);
        setAgentStatusText("");
        setAgentStatusType(undefined);
      } else {
        throw new Error(`Unknown message type: ${msg.type}`);
      }
    });

    es.addEventListener("init", (event) => {
      console.log("Received init message:", event.data);
    });

    return () => {
      es.close();
    };
  }, [chatSessionId]);

  const currentAgent = agents.find((agent) => agent.id === agentId);
  const currentAgentName = currentAgent?.name ?? agentId ?? "unknown";

  return (
    <div className="flex flex-col h-full">
      <div className="h-14 w-full flex items-center border-b border-gray-200 bg-white">
        <div className="ml-auto mr-4 text-lg text-gray-900">
          {chatSessionId ? (
            <>
              <span className="mr-2 font-semibold">智能体:</span>
              <span className="font-normal">{currentAgentName}</span>
            </>
          ) : null}
          <Button
            icon={<SettingOutlined />}
            onClick={openSettingsModal}
            size="small"
            className="ml-3"
          >
            运行设置
          </Button>
        </div>
      </div>

      {chatSessionId ? (
        <>
          <AgentChatHistory
            messages={messages}
            displayAgentStatus={displayAgentStatus}
            agentStatusText={agentStatusText}
            agentStatusType={agentStatusType}
            streamingContent={streamingContent}
          />
          <div className="border-t border-gray-200 p-4 bg-white">
            <AgentChatInput onSend={handleSendMessage} />
          </div>
        </>
      ) : (
        <EmptyAgentChatView
          agents={agents}
          loading={loading}
          handleSendMessage={handleSendMessage}
        />
      )}

      <Modal
        title="RAG 运行设置"
        open={settingsOpen}
        onCancel={() => setSettingsOpen(false)}
        onOk={handleSaveSettings}
        confirmLoading={settingsLoading}
        okText="保存"
        cancelText="取消"
      >
        <Space direction="vertical" size="large" className="w-full">
          <div>
            <div className="mb-2 text-sm text-gray-600">检索模式</div>
            <Radio.Group
              value={runtimeMode}
              onChange={(e) => setRuntimeMode(e.target.value as RagRuntimeMode)}
            >
              <Radio.Button value="fast">快速模式</Radio.Button>
              <Radio.Button value="deep">深度模式</Radio.Button>
            </Radio.Group>
          </div>
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm text-gray-600">Rerank 重排</div>
              <div className="text-xs text-gray-400">关闭后响应更快，排序精度可能下降</div>
            </div>
            <Switch
              checked={rerankEnabled}
              onChange={(checked) => setRerankEnabled(checked)}
            />
          </div>
        </Space>
      </Modal>
    </div>
  );
};

export default AgentChatView;

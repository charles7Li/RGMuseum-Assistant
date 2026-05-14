import { useEffect, useState } from "react";
import {
  createKnowledgeBase,
  type CreateKnowledgeBaseRequest,
  getKnowledgeBases,
} from "../api/api.ts";
import type { KnowledgeBase } from "../types";

export function useKnowledgeBases() {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);

  useEffect(() => {
    async function fetchData() {
      const resp = await getKnowledgeBases();
      // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
      const converted = resp.knowledgeBases.map((kb) => ({
        knowledgeBaseId: kb.id,
        name: kb.name,
        description: kb.description || "",
        embeddingRule: kb.embeddingRule || "title+content(500)",
      }));
      setKnowledgeBases(converted);
    }

    fetchData().then();
  }, []);

  async function createKnowledgeBaseHandle(
    request: CreateKnowledgeBaseRequest,
  ) {
    await createKnowledgeBase(request);
    const resp = await getKnowledgeBases();
    // 将 KnowledgeBaseVO 转换为 KnowledgeBase 类型
    const converted = resp.knowledgeBases.map((kb) => ({
      knowledgeBaseId: kb.id,
      name: kb.name,
      description: kb.description || "",
      embeddingRule: kb.embeddingRule || "title+content(500)",
    }));
    setKnowledgeBases(converted);
  }

  return {
    knowledgeBases,
    createKnowledgeBaseHandle,
  };
}


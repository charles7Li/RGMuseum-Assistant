#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
RAGAS evaluation for JChatMind / RGMA artifact dataset.

Dataset format:
  artifact_dataset/
    artifact_xxx/
      info.json   # must contain qa_pairs: [{q, a, ...}, ...]
      *.jpg

This script will:
1) read qa pairs from info.json
2) call backend to generate answer (chat flow)
3) collect contexts from real agent tool-call traces (optional retrieve fallback)
4) evaluate with ragas
5) save detail + summary + run config
"""

from __future__ import annotations

import argparse
import json
import os
import re
import random
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

import pandas as pd
import requests


def _import_ragas():
    try:
        from ragas import EvaluationDataset, evaluate  # type: ignore
    except Exception:  # pragma: no cover
        from ragas import evaluate  # type: ignore

        EvaluationDataset = None

    # metric names vary across ragas versions
    metric_sources = []
    for module_name in (
        "ragas.metrics._answer_correctness",
        "ragas.metrics._answer_relevance",
        "ragas.metrics._faithfulness",
        "ragas.metrics._context_precision",
        "ragas.metrics._context_recall",
        "ragas.metrics.collections",
        "ragas.metrics",
    ):
        try:
            metric_sources.append(__import__(module_name, fromlist=["*"]))
        except Exception:
            continue
    if not metric_sources:
        raise RuntimeError("Unable to import RAGAS metrics modules.")

    def pick_metric(candidates: List[str]):
        for module in metric_sources:
            for name in candidates:
                if hasattr(module, name):
                    return getattr(module, name), name
        return None, None

    m_answer_correctness, n1 = pick_metric(["answer_correctness", "AnswerCorrectness"])
    m_faithfulness, n2 = pick_metric(["faithfulness", "Faithfulness"])
    m_answer_relevancy, n3 = pick_metric(
        ["answer_relevancy", "response_relevancy", "ResponseRelevancy"]
    )
    m_context_precision, n4 = pick_metric(
        ["context_precision", "llm_context_precision_with_reference", "LLMContextPrecisionWithReference"]
    )
    m_context_recall, n5 = pick_metric(["context_recall", "llm_context_recall", "LLMContextRecall"])

    picked = [
        (m_answer_correctness, n1),
        (m_faithfulness, n2),
        (m_answer_relevancy, n3),
        (m_context_precision, n4),
        (m_context_recall, n5),
    ]
    metrics = [m for m, _ in picked if m is not None]
    metric_names = [n for _, n in picked if n]

    # Tighten the verbose RAGAS prompts so the judge emits shorter JSON and
    # avoids hitting max token limits on long classification outputs.
    _tune_ragas_metric_prompts(metrics)

    if not metrics:
        raise RuntimeError("No supported RAGAS metrics found in current ragas version.")

    return evaluate, EvaluationDataset, metrics, metric_names


def _build_ragas_llm(
    judge_model: str,
    judge_base_url: Optional[str],
    judge_max_tokens: int,
):
    """
    Build a RAGAS-compatible LLM using the current llm_factory API.
    Return None when dependencies are unavailable, then ragas will fallback to its default behavior.
    """
    try:
        from openai import OpenAI  # type: ignore
        from ragas.llms import llm_factory  # type: ignore
    except Exception:
        return None

    api_key = os.getenv("OPENAI_API_KEY")
    base_url = judge_base_url or os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE")
    client = OpenAI(api_key=api_key, base_url=base_url)
    return llm_factory(judge_model, client=client, temperature=0, max_tokens=judge_max_tokens)


def _tune_ragas_metric_prompts(metrics: List[Any]) -> None:
    """
    Shorten RAGAS prompt templates and reduce over-segmentation so the judge
    can finish in a single pass more often.
    """
    statement_prompt_done = False
    classifier_prompt_done = False

    for metric in metrics:
        if getattr(metric, "name", "") == "answer_relevancy" and hasattr(metric, "strictness"):
            try:
                metric.strictness = 1
            except Exception:
                pass

        statement_prompt = getattr(metric, "statement_generator_prompt", None)
        if statement_prompt is not None and not statement_prompt_done:
            prompt_cls = type(statement_prompt)
            prompt_cls.instruction = (
                "Given a question and an answer, extract only the core atomic statements. "
                "Keep the list short, merge minor clauses when possible, avoid over-segmentation, "
                "and return only JSON."
            )
            if hasattr(prompt_cls, "examples"):
                prompt_cls.examples = []
            statement_prompt_done = True

        classifier_prompt = getattr(metric, "correctness_prompt", None)
        if classifier_prompt is not None and not classifier_prompt_done:
            prompt_cls = type(classifier_prompt)
            prompt_cls.instruction = (
                "Given ground truth and answer statements, classify each item as TP, FP, or FN. "
                "Keep reasons short and do not add extra commentary."
            )
            if hasattr(prompt_cls, "examples"):
                prompt_cls.examples = []
            classifier_prompt_done = True


def _build_ragas_embeddings(embedding_model: str, embedding_device: str):
    """
    Build a RAGAS-compatible embeddings wrapper using local HuggingFace model.
    This avoids ragas falling back to OpenAI default embeddings.
    """
    try:
        from ragas.embeddings import LangchainEmbeddingsWrapper  # type: ignore
    except Exception as e:
        raise RuntimeError(
            "Missing ragas embeddings wrapper. Please ensure ragas is installed correctly."
        ) from e

    hf_embeddings_cls = None
    import_errors: List[str] = []
    for module_name in ("langchain_huggingface", "langchain_community.embeddings"):
        try:
            module = __import__(module_name, fromlist=["HuggingFaceEmbeddings"])
            hf_embeddings_cls = getattr(module, "HuggingFaceEmbeddings")
            break
        except Exception as e:
            import_errors.append(f"{module_name}: {e}")

    if hf_embeddings_cls is None:
        raise RuntimeError(
            "Cannot import HuggingFaceEmbeddings. Install `langchain-huggingface` "
            "and `sentence-transformers`. Errors: " + " | ".join(import_errors)
        )

    requested_device = (embedding_device or "").strip() or "cpu"
    actual_device = requested_device
    fallback_reason = ""
    if requested_device.startswith("cuda"):
        try:
            import torch  # type: ignore

            if not torch.cuda.is_available():
                actual_device = "cpu"
                fallback_reason = "torch.cuda.is_available() is False"
        except Exception as e:
            actual_device = "cpu"
            fallback_reason = f"torch import/cuda check failed: {e}"

    model_kwargs: Dict[str, Any] = {"device": actual_device}
    embeddings = hf_embeddings_cls(
        model_name=embedding_model,
        model_kwargs=model_kwargs,
        encode_kwargs={"normalize_embeddings": True},
    )
    return LangchainEmbeddingsWrapper(embeddings), requested_device, actual_device, fallback_reason


def _normalize_for_similarity(text: str) -> str:
    if not text:
        return ""
    t = re.sub(r"\s+", "", str(text).lower())
    t = re.sub(r"[^\w\u4e00-\u9fff]+", "", t)
    return t


def _similarity_proxy(a: str, b: str) -> float:
    na = _normalize_for_similarity(a)
    nb = _normalize_for_similarity(b)
    if not na or not nb:
        return 0.0

    chars_a = set(na)
    chars_b = set(nb)
    char_union = chars_a | chars_b
    char_score = (len(chars_a & chars_b) / len(char_union)) if char_union else 0.0

    def bigrams(s: str) -> set[str]:
        if len(s) < 2:
            return {s} if s else set()
        return {s[i:i+2] for i in range(len(s) - 1)}

    bi_a = bigrams(na)
    bi_b = bigrams(nb)
    bi_union = bi_a | bi_b
    bi_score = (len(bi_a & bi_b) / len(bi_union)) if bi_union else 0.0

    return round(max(0.0, min(1.0, 0.6 * char_score + 0.4 * bi_score)), 6)


@dataclass
class EvalRow:
    artifact_id: str
    artifact_name: str
    question: str
    eval_query: str
    reference: str
    response: str
    retrieved_contexts: List[str]
    session_id: str


@dataclass
class AgentTurnResult:
    response: str
    tool_contexts: List[str]
    tool_message_count: int


class BackendClient:
    def __init__(self, base_url: str, timeout_sec: int = 60) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_sec = timeout_sec
        self.http = requests.Session()

    def _url(self, path: str) -> str:
        if path.startswith("/"):
            return f"{self.base_url}{path}"
        return f"{self.base_url}/{path}"

    def _get(self, path: str) -> Dict[str, Any]:
        r = self.http.get(self._url(path), timeout=self.timeout_sec)
        r.raise_for_status()
        return r.json()

    def _post(self, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        r = self.http.post(self._url(path), json=payload, timeout=self.timeout_sec)
        r.raise_for_status()
        return r.json()

    def _delete(self, path: str) -> None:
        r = self.http.delete(self._url(path), timeout=self.timeout_sec)
        r.raise_for_status()

    def list_agents(self) -> List[Dict[str, Any]]:
        data = self._get("/agents")
        if data.get("code") != 200:
            raise RuntimeError(f"/agents failed: {data}")
        return (data.get("data") or {}).get("agents") or []

    def resolve_agent_id(self, agent_id: str, agent_name: str) -> str:
        if agent_id:
            return agent_id
        agents = self.list_agents()
        if not agents:
            raise RuntimeError("No agent found in backend. Please create one first.")
        if agent_name:
            matches = [a for a in agents if str(a.get("name") or "") == agent_name]
            if len(matches) == 1:
                return str(matches[0].get("id") or "")
            if len(matches) > 1:
                raise RuntimeError(f"Multiple agents match name={agent_name}, please pass --agent-id explicitly.")
            raise RuntimeError(f"Agent not found by name={agent_name}")
        raise RuntimeError("Please pass --agent-id (recommended) or --agent-name. Do not rely on first agent.")

    def create_session(self, agent_id: str, title: str) -> str:
        data = self._post("/chat-sessions", {"agentId": agent_id, "title": title})
        if data.get("code") != 200:
            raise RuntimeError(f"/chat-sessions failed: {data}")
        payload = data.get("data") or {}
        session_id = payload.get("sessionId") or payload.get("chatSessionId")
        if not session_id:
            raise RuntimeError(f"Create session returned empty sessionId: {data}")
        return session_id

    def send_user_message(self, agent_id: str, session_id: str, content: str) -> str:
        payload = {
            "agentId": agent_id,
            "sessionId": session_id,
            "role": "user",
            "content": content,
        }
        data = self._post("/chat-messages", payload)
        if data.get("code") != 200:
            raise RuntimeError(f"/chat-messages failed: {data}")
        msg_id = (data.get("data") or {}).get("chatMessageId")
        if not msg_id:
            raise RuntimeError(f"Create message returned empty chatMessageId: {data}")
        return msg_id

    def get_session_messages(self, session_id: str) -> List[Dict[str, Any]]:
        data = self._get(f"/chat-messages/session/{session_id}")
        if data.get("code") != 200:
            raise RuntimeError(f"/chat-messages/session failed: {data}")
        return (data.get("data") or {}).get("chatMessages") or []

    @staticmethod
    def _extract_contexts_from_tool_content(content: str) -> List[str]:
        text = (content or "").strip()
        if not text:
            return []

        contexts: List[str] = []

        def push(v: Any) -> None:
            s = str(v or "").strip()
            if s:
                contexts.append(s)

        def walk_json(node: Any) -> None:
            if isinstance(node, dict):
                hits = node.get("hits")
                if isinstance(hits, list):
                    for h in hits:
                        if isinstance(h, dict):
                            c = h.get("content")
                            if c:
                                push(c)
                c = node.get("content")
                if isinstance(c, str) and len(c.strip()) >= 20:
                    push(c)
                for v in node.values():
                    walk_json(v)
            elif isinstance(node, list):
                for item in node:
                    walk_json(item)

        # unwrap quoted JSON string payload, e.g. "\"taskType=...\\ntextHits=...\""
        if (text.startswith('"') and text.endswith('"')) or (text.startswith("'") and text.endswith("'")):
            try:
                unwrapped = json.loads(text)
                if isinstance(unwrapped, str):
                    text = unwrapped.strip()
                else:
                    text = str(unwrapped).strip()
            except Exception:
                pass

        parsed = None
        if text.startswith("{") or text.startswith("["):
            try:
                parsed = json.loads(text)
            except Exception:
                parsed = None
        if parsed is not None:
            walk_json(parsed)

        # Parse KnowledgeTool flat payload:
        # taskType=...
        # textHits=
        # 1. ...
        # 2. ...
        # imageHits=...
        if "textHits=" in text:
            start = text.find("textHits=") + len("textHits=")
            end = len(text)
            image_idx = text.find("\nimageHits=", start)
            answer_idx = text.find("\nanswerPolicy=", start)
            if image_idx != -1:
                end = min(end, image_idx)
            if answer_idx != -1:
                end = min(end, answer_idx)
            block = text[start:end].strip()
            if block:
                parts = re.split(r"(?:^|\n)\s*\d+\.\s+", block)
                hit_texts = [p.strip() for p in parts if p and p.strip()]
                if hit_texts:
                    contexts = hit_texts

        # For evaluation, if we cannot parse KnowledgeTool-style contexts,
        # do not fallback to arbitrary tool output (errors/table dumps/Done).
        if not contexts:
            return []

        deduped: List[str] = []
        seen: set[str] = set()
        for c in contexts:
            s = c.strip()
            if not s:
                continue
            if BackendClient._is_noise_context(s):
                continue
            if s not in seen:
                deduped.append(s)
                seen.add(s)
        return deduped

    @staticmethod
    def _is_noise_context(text: str) -> bool:
        lower = text.lower().strip()
        if lower in {"done", "none", "null"}:
            return True
        if "image embedding request failed" in lower:
            return True
        if "bad sql grammar" in lower or "statementcallback" in lower:
            return True
        if "information_schema.columns" in lower:
            return True
        if lower.startswith("查询结果"):
            return True
        if lower.startswith("错误：") or lower.startswith("error:"):
            return True
        if lower.startswith("|") and ("column_name" in lower or "table_name" in lower):
            return True
        return False

    def wait_assistant_turn_result(
        self,
        session_id: str,
        user_message_id: str,
        max_wait_sec: int = 120,
        poll_interval_sec: float = 1.5,
        settle_window_sec: float = 2.5,
    ) -> AgentTurnResult:
        deadline = time.time() + max_wait_sec
        stable_since: Optional[float] = None
        last_tail_signature = ""
        best_assistant_content = ""
        best_final_assistant_content = ""
        best_tool_messages: List[str] = []

        while time.time() < deadline:
            msgs = self.get_session_messages(session_id)

            assistant_content = ""
            assistant_final_content = ""
            last_assistant_idx = -1
            last_tool_idx = -1
            for idx, m in enumerate(msgs):
                role = (m.get("role") or "").lower()
                if role == "tool":
                    last_tool_idx = idx
                elif role == "assistant":
                    content = (m.get("content") or "").strip()
                    if content:
                        assistant_content = content
                        last_assistant_idx = idx
                        tool_calls = (((m.get("metadata") or {}).get("toolCalls")) or [])
                        if not tool_calls:
                            assistant_final_content = content

            tool_messages = [
                (m.get("content") or "").strip()
                for m in msgs
                if (m.get("role") or "").lower() == "tool" and (m.get("content") or "").strip()
            ]
            if assistant_content:
                best_assistant_content = assistant_content
            if assistant_final_content:
                best_final_assistant_content = assistant_final_content
            best_tool_messages = tool_messages

            has_final_candidate = bool(
                assistant_final_content
                and last_tool_idx != -1
                and last_assistant_idx > last_tool_idx
            )
            tail = msgs[-1] if msgs else {}
            tail_signature = f"{len(msgs)}|{tail.get('role','')}|{(tail.get('content') or '')[:80]}"
            now = time.time()
            if tail_signature == last_tail_signature:
                if stable_since is None:
                    stable_since = now
            else:
                stable_since = now
                last_tail_signature = tail_signature

            if has_final_candidate and stable_since is not None and (now - stable_since) >= settle_window_sec:
                tool_contexts: List[str] = []
                for t in tool_messages:
                    tool_contexts.extend(self._extract_contexts_from_tool_content(t))
                deduped: List[str] = []
                seen: set[str] = set()
                for c in tool_contexts:
                    if c not in seen:
                        deduped.append(c)
                        seen.add(c)
                return AgentTurnResult(
                    response=assistant_content,
                    tool_contexts=deduped,
                    tool_message_count=len(tool_messages),
                )
            time.sleep(poll_interval_sec)

        # Timeout fallback: return best observed assistant/tool messages to avoid hard failure.
        if best_final_assistant_content or best_assistant_content:
            fallback_response = best_final_assistant_content or best_assistant_content
            tool_contexts: List[str] = []
            for t in best_tool_messages:
                tool_contexts.extend(self._extract_contexts_from_tool_content(t))
            deduped: List[str] = []
            seen: set[str] = set()
            for c in tool_contexts:
                if c not in seen:
                    deduped.append(c)
                    seen.add(c)
            return AgentTurnResult(
                response=fallback_response,
                tool_contexts=deduped,
                tool_message_count=len(best_tool_messages),
            )

        raise TimeoutError(f"Assistant answer timeout for session={session_id}, userMsg={user_message_id}")

    def retrieve_contexts(self, kb_id: str, query: str, text_topk: int) -> List[str]:
        payload = {"kbId": kb_id, "query": query, "topK": text_topk}
        data = self._post("/rag/retrieve", payload)
        if data.get("code") != 200:
            raise RuntimeError(f"/rag/retrieve failed: {data}")
        hits = (data.get("data") or {}).get("hits") or []
        contexts = []
        for h in hits:
            c = (h.get("content") or "").strip()
            if c:
                contexts.append(c)
        return contexts

    def delete_session(self, session_id: str) -> None:
        try:
            self._delete(f"/chat-sessions/{session_id}")
        except Exception:
            pass


def load_cases(dataset_root: Path, limit: int = 0, sample_seed: Optional[int] = 42) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    folders = sorted([p for p in dataset_root.iterdir() if p.is_dir()])
    for d in folders:
        info_path = d / "info.json"
        if not info_path.exists():
            continue
        with info_path.open("r", encoding="utf-8") as f:
            info = json.load(f)
        artifact_id = str(info.get("id") or d.name)
        artifact_name = str(info.get("name") or info.get("display_name") or artifact_id)
        qa_pairs = info.get("qa_pairs") or []
        for qa in qa_pairs:
            q = str(qa.get("q") or "").strip()
            a = str(qa.get("a") or "").strip()
            if not q or not a:
                continue
            rows.append(
                {
                    "artifact_id": artifact_id,
                    "artifact_name": artifact_name,
                    "question": q,
                    "reference": a,
                }
            )
    if rows and sample_seed is not None:
        rng = random.Random(sample_seed)
        rng.shuffle(rows)
    if limit > 0:
        return rows[:limit]
    return rows


def build_eval_query(question: str, artifact_name: str, artifact_id: str, query_template: str) -> str:
    """
    Build evaluation query with contextual hint to improve retrieval/tool-calling.
    Supported placeholders: {artifact_name}, {artifact_id}, {question}
    """
    if not query_template:
        return question
    return query_template.format(
        artifact_name=artifact_name,
        artifact_id=artifact_id,
        question=question,
    )


def build_retrieve_query(question: str, artifact_name: str) -> str:
    """
    Build a compact retrieval query that avoids loading the full evaluation prompt
    into the retriever.
    """
    parts = [artifact_name.strip(), question.strip()]
    return " ".join([p for p in parts if p])


def run_eval(
    api_base: str,
    agent_id: str,
    agent_name: str,
    kb_id: str,
    dataset_root: Path,
    out_detail: Path,
    out_summary: Path,
    out_run_config: Path,
    text_topk: int,
    context_source: str,
    limit: int,
    sample_seed: Optional[int],
    max_wait_sec: int,
    judge_model: str,
    judge_base_url: Optional[str],
    judge_max_tokens: int,
    embedding_model: str,
    embedding_device: str,
    query_template: str,
    allow_no_embeddings: bool,
    delete_session_after_each: bool,
    delete_session_delay_sec: float,
    disable_image_tool: bool,
) -> None:
    evaluate, EvaluationDataset, metrics, metric_names = _import_ragas()
    ragas_llm = _build_ragas_llm(
        judge_model=judge_model,
        judge_base_url=judge_base_url,
        judge_max_tokens=judge_max_tokens,
    )
    ragas_embeddings = None
    requested_embedding_device = embedding_device
    actual_embedding_device = embedding_device
    embedding_device_fallback_reason = ""
    embeddings_enabled = True
    try:
        (
            ragas_embeddings,
            requested_embedding_device,
            actual_embedding_device,
            embedding_device_fallback_reason,
        ) = _build_ragas_embeddings(
            embedding_model=embedding_model,
            embedding_device=embedding_device,
        )
    except Exception as e:
        if not allow_no_embeddings:
            raise
        embeddings_enabled = False
        removed = {"answer_relevancy", "response_relevancy", "ResponseRelevancy", "answer_correctness", "AnswerCorrectness"}
        filtered_metrics = []
        filtered_names = []
        for m, n in zip(metrics, metric_names):
            if n in removed:
                continue
            filtered_metrics.append(m)
            filtered_names.append(n)
        metrics = filtered_metrics
        metric_names = filtered_names
        print(f"[WARN] Embeddings disabled, fallback metrics only. reason={e}")

    # Print device decision early so long eval runs don't hide this signal.
    actual_for_print = actual_embedding_device if embeddings_enabled else "N/A (disabled)"
    print(f"[Init] RAGAS local embeddings enabled: {embeddings_enabled}")
    print(f"[Init] RAGAS embedding model: {embedding_model}")
    print(f"[Init] RAGAS embedding device requested: {requested_embedding_device}")
    print(f"[Init] RAGAS embedding device actual: {actual_for_print}")
    print("[Init] Backend retrieval embeddings: unaffected by this switch")
    print(f"[Init] Judge max tokens: {judge_max_tokens}")
    if embedding_device_fallback_reason:
        print(f"[Init] RAGAS embedding fallback reason: {embedding_device_fallback_reason}")
    client = BackendClient(api_base)
    resolved_agent_id = client.resolve_agent_id(agent_id=agent_id, agent_name=agent_name)

    base_rows = load_cases(dataset_root, limit=limit, sample_seed=sample_seed)
    if not base_rows:
        raise RuntimeError(f"No qa_pairs found under {dataset_root}")

    eval_rows: List[EvalRow] = []
    errors: List[Dict[str, Any]] = []
    tool_call_rows = 0
    tool_message_total = 0
    retrieve_fallback_rows = 0

    for i, row in enumerate(base_rows, start=1):
        q = row["question"]
        artifact_name = row["artifact_name"]
        eval_query = build_eval_query(
            question=q,
            artifact_name=artifact_name,
            artifact_id=row["artifact_id"],
            query_template=query_template,
        )
        retrieve_query = build_retrieve_query(question=q, artifact_name=artifact_name)
        if disable_image_tool:
            eval_query = (
                eval_query
                + "\n额外要求：仅调用文本知识库工具 KnowledgeTool，不要调用 ImageKnowledgeTool。"
                + "不要调用 databaseQuery 或 dataBaseTool。"
                + "直接给出最终答案，不要输出检索过程、思考过程、前言或自我说明。"
                + "如果证据不足，请直接回复“知识库证据不足，无法给出确定答案”。"
            )
        session_title = f"ragas-{uuid.uuid4().hex[:8]}"
        session_id = ""
        try:
            session_id = client.create_session(resolved_agent_id, session_title)
            user_msg_id = client.send_user_message(resolved_agent_id, session_id, eval_query)
            turn_result = client.wait_assistant_turn_result(
                session_id=session_id,
                user_message_id=user_msg_id,
                max_wait_sec=max_wait_sec,
            )

            agent_contexts = turn_result.tool_contexts
            retrieve_contexts: List[str] = []
            should_fetch_retrieve = context_source in ("retrieve", "hybrid") or not agent_contexts
            if should_fetch_retrieve:
                retrieve_contexts = client.retrieve_contexts(kb_id=kb_id, query=retrieve_query, text_topk=text_topk)
                if not agent_contexts and retrieve_contexts:
                    retrieve_fallback_rows += 1

            if context_source == "agent":
                contexts = agent_contexts if agent_contexts else retrieve_contexts
            elif context_source == "retrieve":
                contexts = retrieve_contexts
            else:
                contexts = []
                seen: set[str] = set()
                for c in agent_contexts + retrieve_contexts:
                    if c not in seen:
                        contexts.append(c)
                        seen.add(c)

            if turn_result.tool_message_count > 0:
                tool_call_rows += 1
            tool_message_total += turn_result.tool_message_count

            eval_rows.append(
                EvalRow(
                    artifact_id=row["artifact_id"],
                    artifact_name=artifact_name,
                    question=q,
                    eval_query=eval_query,
                    reference=row["reference"],
                    response=turn_result.response,
                    retrieved_contexts=contexts,
                    session_id=session_id,
                )
            )
            print(f"[{i}/{len(base_rows)}] ok: {row['artifact_id']} | {q[:40]}")
        except Exception as e:
            errors.append(
                {
                    "artifact_id": row["artifact_id"],
                    "question": q,
                    "error": str(e),
                    "session_id": session_id,
                }
            )
            print(f"[{i}/{len(base_rows)}] error: {row['artifact_id']} | {e}")
        finally:
            if session_id and delete_session_after_each:
                if delete_session_delay_sec > 0:
                    time.sleep(delete_session_delay_sec)
                client.delete_session(session_id)

    if not eval_rows:
        raise RuntimeError("No successful samples to evaluate.")

    ragas_rows = [
        {
            "user_input": r.eval_query,
            "response": r.response,
            "reference": r.reference,
            "retrieved_contexts": r.retrieved_contexts,
            "artifact_id": r.artifact_id,
            "artifact_name": r.artifact_name,
            "session_id": r.session_id,
        }
        for r in eval_rows
    ]

    if EvaluationDataset is not None:
        dataset = EvaluationDataset.from_list(ragas_rows)
    else:
        from datasets import Dataset  # type: ignore

        dataset = Dataset.from_list(ragas_rows)

    eval_kwargs: Dict[str, Any] = {"dataset": dataset, "metrics": metrics}
    if embeddings_enabled and ragas_embeddings is not None:
        eval_kwargs["embeddings"] = ragas_embeddings
    if ragas_llm is not None:
        eval_kwargs["llm"] = ragas_llm
    result = evaluate(**eval_kwargs)

    if hasattr(result, "to_pandas"):
        score_df = result.to_pandas()
    elif hasattr(result, "scores"):
        score_df = pd.DataFrame(result.scores)
    else:
        score_df = pd.DataFrame(result)

    meta_df = pd.DataFrame(
        [
            {
                "meta_artifact_id": r.artifact_id,
                "meta_artifact_name": r.artifact_name,
                "meta_question": r.question,
                "meta_eval_query": r.eval_query,
                "meta_reference": r.reference,
                "meta_response": r.response,
                "meta_retrieved_contexts": "\n---\n".join(r.retrieved_contexts),
                "meta_session_id": r.session_id,
            }
            for r in eval_rows
        ]
    )
    score_df = score_df.drop(columns=[c for c in score_df.columns if c in meta_df.columns], errors="ignore")
    detail_df = pd.concat([meta_df.reset_index(drop=True), score_df.reset_index(drop=True)], axis=1)

    # Fallback metrics: keep numeric values available when RAGAS returns NaN.
    if "answer_correctness" not in detail_df.columns:
        detail_df["answer_correctness"] = pd.NA
    if "answer_relevancy" not in detail_df.columns:
        detail_df["answer_relevancy"] = pd.NA
    if "meta_response" in detail_df.columns and "meta_reference" in detail_df.columns:
        fallback_correctness = [
            _similarity_proxy(resp, ref)
            for resp, ref in zip(detail_df["meta_response"].astype(str), detail_df["meta_reference"].astype(str))
        ]
        detail_df["answer_correctness"] = pd.to_numeric(detail_df["answer_correctness"], errors="coerce").fillna(
            pd.Series(fallback_correctness, index=detail_df.index)
        )
    if "meta_response" in detail_df.columns and "meta_question" in detail_df.columns:
        fallback_relevancy = [
            _similarity_proxy(resp, q)
            for resp, q in zip(detail_df["meta_response"].astype(str), detail_df["meta_question"].astype(str))
        ]
        detail_df["answer_relevancy"] = pd.to_numeric(detail_df["answer_relevancy"], errors="coerce").fillna(
            pd.Series(fallback_relevancy, index=detail_df.index)
        )

    out_detail.parent.mkdir(parents=True, exist_ok=True)
    out_summary.parent.mkdir(parents=True, exist_ok=True)
    detail_df.to_csv(out_detail, index=False, encoding="utf-8-sig")

    metric_cols = [c for c in score_df.columns if c in metric_names or c in [m.__name__ if hasattr(m, "__name__") else "" for m in metrics]]
    if not metric_cols:
        metric_cols = [c for c in score_df.columns if pd.api.types.is_numeric_dtype(score_df[c])]

    summary: Dict[str, Any] = {
        "total_cases": len(base_rows),
        "evaluated_cases": len(eval_rows),
        "failed_cases": len(errors),
        "agent_id": resolved_agent_id,
        "context_source": context_source,
        "tool_called_rows": tool_call_rows,
        "tool_called_ratio": (tool_call_rows / len(eval_rows)) if eval_rows else 0.0,
        "tool_message_total": tool_message_total,
        "retrieve_fallback_rows": retrieve_fallback_rows,
        "metrics": {},
    }
    for c in metric_cols:
        summary["metrics"][c] = float(detail_df[c].mean(skipna=True))
    if errors:
        summary["errors"] = errors[:50]

    with out_summary.open("w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    run_config = {
        "api_base": api_base,
        "agent_id": resolved_agent_id,
        "agent_name": agent_name,
        "kb_id": kb_id,
        "dataset_root": str(dataset_root),
        "out_detail": str(out_detail),
        "out_summary": str(out_summary),
        "out_run_config": str(out_run_config),
        "text_topk": text_topk,
        "context_source": context_source,
        "limit": limit,
        "sample_seed": sample_seed,
        "max_wait_sec": max_wait_sec,
        "judge_model": judge_model,
        "judge_base_url": judge_base_url,
        "judge_max_tokens": judge_max_tokens,
        "embedding_model": embedding_model,
        "embedding_device_requested": requested_embedding_device,
        "embedding_device_actual": actual_embedding_device,
        "embedding_device_fallback_reason": embedding_device_fallback_reason,
        "embeddings_enabled": embeddings_enabled,
        "query_template": query_template,
        "delete_session_after_each": delete_session_after_each,
        "delete_session_delay_sec": delete_session_delay_sec,
        "disable_image_tool": disable_image_tool,
        "retrieve_fallback_rows": retrieve_fallback_rows,
        "summary": summary,
    }
    out_run_config.parent.mkdir(parents=True, exist_ok=True)
    with out_run_config.open("w", encoding="utf-8") as f:
        json.dump(run_config, f, ensure_ascii=False, indent=2)

    print("")
    print("RAGAS done.")
    print(f"Detail : {out_detail}")
    print(f"Summary: {out_summary}")
    print(f"Run config: {out_run_config}")
    print(f"Metric names (resolved): {metric_names}")
    print(f"Evaluated: {len(eval_rows)} / {len(base_rows)}")
    print(f"Sample seed: {sample_seed}")
    print(f"Agent id: {resolved_agent_id}")
    print(f"Context source: {context_source}")
    print(f"Rows with tool calls: {tool_call_rows} / {len(eval_rows)}")
    print(f"Rows with retrieve fallback: {retrieve_fallback_rows}")
    print(f"Judge model: {judge_model}")
    print(f"Judge max tokens: {judge_max_tokens}")
    actual_for_print = actual_embedding_device if embeddings_enabled else "N/A (disabled)"
    print(f"RAGAS embedding model: {embedding_model}")
    print(f"RAGAS local embeddings enabled: {embeddings_enabled}")
    print(f"RAGAS embedding device requested: {requested_embedding_device}")
    print(f"RAGAS embedding device actual: {actual_for_print}")
    print("Backend retrieval embeddings: unaffected by this switch")
    if embedding_device_fallback_reason:
        print(f"RAGAS embedding fallback reason: {embedding_device_fallback_reason}")
    if judge_base_url:
        print(f"Judge base url: {judge_base_url}")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Run RAGAS evaluation on artifact_dataset")
    p.add_argument("--api-base", default="http://localhost:8080/api", help="Backend API base url")
    p.add_argument("--agent-id", default="", help="Agent id for chat evaluation (recommended)")
    p.add_argument("--agent-name", default="", help="Agent name if agent-id is not provided")
    p.add_argument("--kb-id", required=True, help="Knowledge base id (for retrieve/hybrid context modes)")
    p.add_argument(
        "--dataset-root",
        default=r"C:\Code\github\JChatMind\data\datasets\museum\data\artifact_dataset",
        help="artifact_dataset root",
    )
    p.add_argument(
        "--out-detail",
        default=r"C:\Code\github\JChatMind\data\datasets\museum\data\artifact_dataset\ragas_detail.csv",
    )
    p.add_argument(
        "--out-summary",
        default=r"C:\Code\github\JChatMind\data\datasets\museum\data\artifact_dataset\ragas_summary.json",
    )
    p.add_argument(
        "--out-run-config",
        default=r"C:\Code\github\JChatMind\data\datasets\museum\data\artifact_dataset\ragas_run_config.json",
    )
    p.add_argument("--text-topk", type=int, default=5)
    p.add_argument(
        "--context-source",
        default="agent",
        choices=["agent", "retrieve", "hybrid"],
        help="context source for ragas: agent tool traces, retrieve api, or hybrid",
    )
    p.add_argument("--limit", type=int, default=0, help="0 means all")
    p.add_argument(
        "--sample-seed",
        type=int,
        default=42,
        help="shuffle seed for sampling qa pairs before applying --limit",
    )
    p.add_argument("--max-wait-sec", type=int, default=120, help="wait assistant answer timeout")
    p.add_argument("--judge-model", default="gpt-4o-mini", help="judge model for ragas")
    p.add_argument("--judge-base-url", default="", help="override OpenAI-compatible base url for judge")
    p.add_argument(
        "--judge-max-tokens",
        type=int,
        default=8192,
        help="max output tokens for judge LLM calls",
    )
    p.add_argument(
        "--embedding-model",
        default="BAAI/bge-m3",
        help="local embedding model for ragas metrics (default: BAAI/bge-m3)",
    )
    p.add_argument(
        "--embedding-device",
        default="cpu",
        help="embedding device, e.g. cpu / cuda / cuda:0",
    )
    p.add_argument(
        "--query-template",
        default=(
            "你正在执行知识库问答评测。请先调用知识库检索工具，再直接回答用户问题。"
            "只输出最终答案，不要写检索过程、思考过程、前言、自我说明或分点编号。"
            "如果证据不足，请直接回复“知识库证据不足，无法给出确定答案”。"
            "目标文物ID：{artifact_id}。"
            "目标文物：{artifact_name}。"
            "用户问题：{question}"
        ),
        help="query template with placeholders: {artifact_name}, {artifact_id}, {question}",
    )
    p.add_argument(
        "--allow-no-embeddings",
        action="store_true",
        help="allow fallback mode when local embedding dependencies are unavailable",
    )
    p.add_argument(
        "--disable-image-tool",
        action="store_true",
        help="append instruction to avoid ImageKnowledgeTool and prefer text-only KnowledgeTool",
    )
    p.add_argument(
        "--delete-session-after-each",
        action="store_true",
        help="delete chat session after each sample (off by default to avoid async FK race)",
    )
    p.add_argument(
        "--delete-session-delay-sec",
        type=float,
        default=3.0,
        help="delay before deleting session when --delete-session-after-each is enabled",
    )
    return p.parse_args()


if __name__ == "__main__":
    args = parse_args()
    run_eval(
        api_base=args.api_base,
        agent_id=args.agent_id,
        agent_name=args.agent_name,
        kb_id=args.kb_id,
        dataset_root=Path(args.dataset_root),
        out_detail=Path(args.out_detail),
        out_summary=Path(args.out_summary),
        out_run_config=Path(args.out_run_config),
        text_topk=args.text_topk,
        context_source=args.context_source,
        limit=args.limit,
        sample_seed=args.sample_seed,
        max_wait_sec=args.max_wait_sec,
        judge_model=args.judge_model,
        judge_base_url=(args.judge_base_url or None),
        judge_max_tokens=args.judge_max_tokens,
        embedding_model=args.embedding_model,
        embedding_device=args.embedding_device,
        query_template=args.query_template,
        allow_no_embeddings=args.allow_no_embeddings,
        delete_session_after_each=args.delete_session_after_each,
        delete_session_delay_sec=args.delete_session_delay_sec,
        disable_image_tool=args.disable_image_tool,
    )


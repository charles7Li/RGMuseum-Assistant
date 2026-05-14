# RGMuseum-Assistant

RGMuseum-Assistant is an intelligent museum retrieval assistant. It combines knowledge-base search, image/document retrieval, reranking, and conversational interaction to help users explore museum collections more naturally.

## Overview

This project is designed for museum scenarios where users need to search, ask questions, and understand cultural relics or exhibition materials. The assistant can retrieve relevant text, documents, and image-related knowledge from a local knowledge base, then use an AI chat workflow to generate helpful answers.

## Features

- Conversational museum knowledge assistant
- Document upload, parsing, storage, and retrieval
- Text-based RAG retrieval with dense and lexical recall
- Optional rerank service for higher-quality search results
- Image knowledge retrieval for cultural relic datasets
- Museum route and map-related assistant tools
- Web UI for managing agents, knowledge bases, and chat sessions

## Tech Stack

- Backend: Spring Boot, MyBatis-Plus, PostgreSQL
- Frontend: React, TypeScript, Vite, Tailwind CSS
- Retrieval: RAG pipeline, Milvus vector search, BGE embeddings
- Rerank service: Python FastAPI service
- AI providers: DeepSeek, ZhipuAI, OpenAI-compatible APIs

## Configuration

Sensitive values should be provided through environment variables or a local `.env` file. Do not commit real API keys, email authorization codes, database passwords, or service tokens.

Common environment variables:

```env
DB_PASSWORD=
EMAIL_USERNAME=
EMAIL_PASSWORD=
DEEPSEEK_API_KEY=
ZHIPUAI_API_KEY=
OPENAI_API_KEY=
AMAP_API_KEY=
```

## Project Structure

```text
jchatmind/        Spring Boot backend
ui/               React frontend
rerank_service/   Optional Python rerank service
scripts/          Dataset import and evaluation scripts
data/             Local datasets and document storage
```

## Notes

Before publishing this repository, review local data files and Git history to make sure no private datasets, credentials, logs, or generated artifacts are included.

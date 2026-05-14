import base64
import os
import traceback
from io import BytesIO
from typing import List

import torch
from fastapi import FastAPI, Request
from PIL import Image
from pydantic import BaseModel
from transformers import (
    AutoModel,
    AutoModelForSequenceClassification,
    AutoProcessor,
    AutoTokenizer,
    BitsAndBytesConfig,
)


RERANK_MODEL_NAME = os.getenv("RERANK_MODEL", "BAAI/bge-reranker-v2-m3")
RERANK_DEVICE = os.getenv("RERANK_DEVICE", "cuda" if torch.cuda.is_available() else "cpu")
RERANK_USE_4BIT = os.getenv("RERANK_USE_4BIT", "true").lower() in ("1", "true", "yes", "on")
RERANK_MAX_LENGTH = int(os.getenv("RERANK_MAX_LENGTH", "512"))
RERANK_BATCH_SIZE = int(os.getenv("RERANK_BATCH_SIZE", "1"))

IMAGE_EMBED_MODEL_NAME = os.getenv("IMAGE_EMBED_MODEL", "OFA-Sys/chinese-clip-vit-base-patch16")
IMAGE_EMBED_DEVICE = os.getenv("IMAGE_EMBED_DEVICE", RERANK_DEVICE)
IMAGE_EMBED_BATCH_SIZE = int(os.getenv("IMAGE_EMBED_BATCH_SIZE", "8"))


class RerankResult(BaseModel):
    index: int
    relevance_score: float


class RerankResponse(BaseModel):
    model: str
    results: List[RerankResult]
    scores: List[float]


class EmbedResponse(BaseModel):
    model: str
    embeddings: List[List[float]]
    dim: int


def _load_rerank_model(model_name: str):
    tokenizer = AutoTokenizer.from_pretrained(model_name, trust_remote_code=True)
    if tokenizer.pad_token_id is None:
        if tokenizer.eos_token_id is not None:
            tokenizer.pad_token = tokenizer.eos_token
        elif tokenizer.unk_token_id is not None:
            tokenizer.pad_token = tokenizer.unk_token
        else:
            tokenizer.add_special_tokens({"pad_token": "[PAD]"})

    model_kwargs = {"trust_remote_code": True}
    if RERANK_USE_4BIT and RERANK_DEVICE.startswith("cuda"):
        model_kwargs["quantization_config"] = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_use_double_quant=True,
            bnb_4bit_compute_dtype=torch.float16,
        )
        model_kwargs["device_map"] = "auto"
    else:
        model_kwargs["torch_dtype"] = torch.float16 if RERANK_DEVICE.startswith("cuda") else torch.float32

    model = AutoModelForSequenceClassification.from_pretrained(model_name, **model_kwargs)
    if model.config.pad_token_id is None:
        model.config.pad_token_id = tokenizer.pad_token_id
    if not RERANK_USE_4BIT or not RERANK_DEVICE.startswith("cuda"):
        model.to(RERANK_DEVICE)
    model.eval()
    return tokenizer, model


def _score_pairs(query: str, docs: List[str], tokenizer, model) -> List[float]:
    scores: List[float] = []
    model_device = next(model.parameters()).device
    with torch.no_grad():
        for i in range(0, len(docs), RERANK_BATCH_SIZE):
            batch_docs = docs[i:i + RERANK_BATCH_SIZE]
            encoded = tokenizer(
                [query] * len(batch_docs),
                batch_docs,
                truncation=True,
                padding=True,
                max_length=RERANK_MAX_LENGTH,
                return_tensors="pt",
            )
            encoded = {k: v.to(model_device) for k, v in encoded.items()}
            outputs = model(**encoded)
            logits = outputs.logits
            if logits.ndim == 1:
                batch_scores = logits.float().cpu().tolist()
            elif logits.shape[-1] == 1:
                batch_scores = logits.squeeze(-1).float().cpu().tolist()
            else:
                batch_scores = logits[:, -1].float().cpu().tolist()
            scores.extend([float(s) for s in batch_scores])
    return scores


def _load_clip_model(model_name: str):
    processor = AutoProcessor.from_pretrained(model_name, trust_remote_code=True)
    model = AutoModel.from_pretrained(model_name, trust_remote_code=True)
    model.to(IMAGE_EMBED_DEVICE)
    model.eval()
    return processor, model


def _normalize_embeddings(t: torch.Tensor) -> torch.Tensor:
    return t / t.norm(dim=-1, keepdim=True).clamp(min=1e-12)


def _embed_texts(texts: List[str], processor, model) -> List[List[float]]:
    vectors: List[List[float]] = []
    with torch.no_grad():
        for i in range(0, len(texts), IMAGE_EMBED_BATCH_SIZE):
            batch = texts[i:i + IMAGE_EMBED_BATCH_SIZE]
            encoded = processor(text=batch, return_tensors="pt", padding=True, truncation=True)
            encoded = {k: v.to(IMAGE_EMBED_DEVICE) for k, v in encoded.items()}
            feats = model.get_text_features(**encoded)
            feats = _normalize_embeddings(feats).float().cpu().tolist()
            vectors.extend([[float(x) for x in row] for row in feats])
    return vectors


def _decode_image_base64(s: str) -> Image.Image:
    raw = base64.b64decode(s)
    return Image.open(BytesIO(raw)).convert("RGB")


def _embed_images(image_base64_list: List[str], processor, model) -> List[List[float]]:
    vectors: List[List[float]] = []
    images = [_decode_image_base64(x) for x in image_base64_list]
    with torch.no_grad():
        for i in range(0, len(images), IMAGE_EMBED_BATCH_SIZE):
            batch = images[i:i + IMAGE_EMBED_BATCH_SIZE]
            encoded = processor(images=batch, return_tensors="pt")
            encoded = {k: v.to(IMAGE_EMBED_DEVICE) for k, v in encoded.items()}
            feats = model.get_image_features(**encoded)
            feats = _normalize_embeddings(feats).float().cpu().tolist()
            vectors.extend([[float(x) for x in row] for row in feats])
    return vectors


app = FastAPI(title="Local Python AI Service")
rerank_tokenizer, rerank_model = _load_rerank_model(RERANK_MODEL_NAME)
clip_processor, clip_model = _load_clip_model(IMAGE_EMBED_MODEL_NAME)


@app.get("/health")
def health():
    return {
        "ok": True,
        "rerank_model": RERANK_MODEL_NAME,
        "rerank_device": RERANK_DEVICE,
        "rerank_use_4bit": RERANK_USE_4BIT,
        "rerank_max_length": RERANK_MAX_LENGTH,
        "rerank_batch_size": RERANK_BATCH_SIZE,
        "image_embed_model": IMAGE_EMBED_MODEL_NAME,
        "image_embed_device": IMAGE_EMBED_DEVICE,
        "image_embed_batch_size": IMAGE_EMBED_BATCH_SIZE,
    }


@app.post("/rerank", response_model=RerankResponse)
async def rerank(request: Request):
    try:
        payload = await request.json()
    except Exception:
        payload = {}
    if not isinstance(payload, dict):
        payload = {}

    query_raw = payload.get("query")
    docs_raw = payload.get("documents")
    top_n_raw = payload.get("top_n")
    model_raw = payload.get("model")

    documents = docs_raw if isinstance(docs_raw, list) else []
    if not documents:
        return RerankResponse(model=RERANK_MODEL_NAME, results=[], scores=[])
    query = (query_raw if isinstance(query_raw, str) else "").strip() or " "

    active_model = model_raw if isinstance(model_raw, str) and model_raw.strip() else RERANK_MODEL_NAME
    if active_model != RERANK_MODEL_NAME:
        print(f"[warn] ignore request model={active_model}, using boot model={RERANK_MODEL_NAME}")

    normalized_docs = [(d if isinstance(d, str) else ("" if d is None else str(d))) for d in documents]
    try:
        scores = _score_pairs(query, normalized_docs, rerank_tokenizer, rerank_model)
    except Exception as e:
        print(f"[error] rerank inference failed: {e}")
        traceback.print_exc()
        scores = [0.0 for _ in normalized_docs]

    indexed = [{"index": i, "relevance_score": scores[i]} for i in range(len(scores))]
    indexed.sort(key=lambda x: x["relevance_score"], reverse=True)
    top_n = top_n_raw if isinstance(top_n_raw, int) else None
    if top_n is not None and top_n > 0:
        indexed = indexed[: min(top_n, len(indexed))]
    results = [RerankResult(index=item["index"], relevance_score=float(item["relevance_score"])) for item in indexed]
    return RerankResponse(model=RERANK_MODEL_NAME, results=results, scores=scores)


@app.post("/embed-text", response_model=EmbedResponse)
async def embed_text(request: Request):
    try:
        payload = await request.json()
    except Exception:
        payload = {}
    if not isinstance(payload, dict):
        payload = {}

    model_raw = payload.get("model")
    text_raw = payload.get("text")
    texts_raw = payload.get("texts")
    active_model = model_raw if isinstance(model_raw, str) and model_raw.strip() else IMAGE_EMBED_MODEL_NAME
    if active_model != IMAGE_EMBED_MODEL_NAME:
        print(f"[warn] ignore request model={active_model}, using boot model={IMAGE_EMBED_MODEL_NAME}")

    texts: List[str] = []
    if isinstance(text_raw, str) and text_raw.strip():
        texts.append(text_raw)
    if isinstance(texts_raw, list):
        texts.extend([x for x in texts_raw if isinstance(x, str) and x.strip()])
    if not texts:
        texts = [" "]

    try:
        vectors = _embed_texts(texts, clip_processor, clip_model)
    except Exception as e:
        print(f"[error] embed-text failed: {e}")
        traceback.print_exc()
        vectors = [[0.0] * 512 for _ in texts]
    dim = len(vectors[0]) if vectors else 0
    return EmbedResponse(model=IMAGE_EMBED_MODEL_NAME, embeddings=vectors, dim=dim)


@app.post("/embed-image", response_model=EmbedResponse)
async def embed_image(request: Request):
    try:
        payload = await request.json()
    except Exception:
        payload = {}
    if not isinstance(payload, dict):
        payload = {}

    model_raw = payload.get("model")
    image_raw = payload.get("image")
    images_raw = payload.get("images")
    active_model = model_raw if isinstance(model_raw, str) and model_raw.strip() else IMAGE_EMBED_MODEL_NAME
    if active_model != IMAGE_EMBED_MODEL_NAME:
        print(f"[warn] ignore request model={active_model}, using boot model={IMAGE_EMBED_MODEL_NAME}")

    images: List[str] = []
    if isinstance(image_raw, str) and image_raw.strip():
        images.append(image_raw)
    if isinstance(images_raw, list):
        images.extend([x for x in images_raw if isinstance(x, str) and x.strip()])
    if not images:
        return EmbedResponse(model=IMAGE_EMBED_MODEL_NAME, embeddings=[], dim=0)

    try:
        vectors = _embed_images(images, clip_processor, clip_model)
    except Exception as e:
        print(f"[error] embed-image failed: {e}")
        traceback.print_exc()
        vectors = [[0.0] * 512 for _ in images]
    dim = len(vectors[0]) if vectors else 0
    return EmbedResponse(model=IMAGE_EMBED_MODEL_NAME, embeddings=vectors, dim=dim)

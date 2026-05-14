import argparse
import base64
import json
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import psycopg2
import requests
from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility


def ensure_text_collection(name: str, dim: int, recreate: bool) -> Collection:
    if utility.has_collection(name):
        if recreate:
            utility.drop_collection(name)
        else:
            return Collection(name=name)

    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, max_length=64, is_primary=True, auto_id=False),
        FieldSchema(name="kb_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="doc_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=16384),
        FieldSchema(name="metadata", dtype=DataType.VARCHAR, max_length=8192),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dim),
    ]
    schema = CollectionSchema(fields=fields, description="text chunks + embeddings")
    collection = Collection(name=name, schema=schema)
    collection.create_index(
        field_name="embedding",
        index_params={"index_type": "IVF_FLAT", "metric_type": "L2", "params": {"nlist": 1024}},
    )
    return collection


def ensure_image_collection(name: str, dim: int, recreate: bool) -> Collection:
    if utility.has_collection(name):
        if recreate:
            utility.drop_collection(name)
        else:
            return Collection(name=name)

    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, max_length=64, is_primary=True, auto_id=False),
        FieldSchema(name="kb_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="doc_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="file_name", dtype=DataType.VARCHAR, max_length=512),
        FieldSchema(name="file_path", dtype=DataType.VARCHAR, max_length=2048),
        FieldSchema(name="metadata", dtype=DataType.VARCHAR, max_length=8192),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dim),
    ]
    schema = CollectionSchema(fields=fields, description="image metadata + embeddings")
    collection = Collection(name=name, schema=schema)
    collection.create_index(
        field_name="embedding",
        index_params={"index_type": "IVF_FLAT", "metric_type": "L2", "params": {"nlist": 1024}},
    )
    return collection


def safe_text(value: Optional[str], max_len: int) -> str:
    if value is None:
        return ""
    text = str(value)
    if len(text) > max_len:
        return text[:max_len]
    return text


def embed_text(base_url: str, model: str, text: str, timeout: int) -> List[float]:
    payload = {"model": model, "prompt": text}
    resp = requests.post(f"{base_url.rstrip('/')}/api/embeddings", json=payload, timeout=timeout)
    resp.raise_for_status()
    data = resp.json()
    embedding = data.get("embedding")
    if not isinstance(embedding, list) or not embedding:
        raise RuntimeError(f"invalid text embedding response: {data}")
    return [float(x) for x in embedding]


def embed_image(base_url: str, model: str, image_bytes: bytes, timeout: int) -> List[float]:
    payload = {"model": model, "image": base64.b64encode(image_bytes).decode("utf-8")}
    resp = requests.post(f"{base_url.rstrip('/')}/embed-image", json=payload, timeout=timeout)
    resp.raise_for_status()
    data = resp.json()
    embedding = data.get("embedding")
    if isinstance(embedding, list) and embedding:
        return [float(x) for x in embedding]
    embeddings = data.get("embeddings")
    if isinstance(embeddings, list) and embeddings and isinstance(embeddings[0], list):
        return [float(x) for x in embeddings[0]]
    raise RuntimeError(f"invalid image embedding response: {data}")


def parse_document_file_path(metadata_text: Optional[str]) -> Optional[str]:
    if not metadata_text:
        return None
    try:
        metadata = json.loads(metadata_text)
        file_path = metadata.get("filePath")
        if isinstance(file_path, str) and file_path.strip():
            return file_path.strip()
    except Exception:
        return None
    return None


def rebuild_text(conn, text_collection: Collection, args):
    cur = conn.cursor()
    cur.execute(
        """
        SELECT id::text, kb_id::text, doc_id::text, content, metadata::text
        FROM chunk_bge_m3
        ORDER BY created_at ASC
        """
    )

    total = 0
    ok = 0
    fail = 0
    batch_rows: List[Tuple[str, str, str, str, str, List[float]]] = []

    for row in cur:
        total += 1
        row_id, kb_id, doc_id, content, metadata = row
        content_text = safe_text(content, 16384)
        if not content_text:
            fail += 1
            continue
        try:
            vec = embed_text(args.text_embed_base_url, args.text_embed_model, content_text, args.timeout_sec)
            if len(vec) != args.text_dim:
                raise RuntimeError(f"dim mismatch: got={len(vec)}, expected={args.text_dim}")
            batch_rows.append((
                safe_text(row_id, 64),
                safe_text(kb_id, 64),
                safe_text(doc_id, 64),
                content_text,
                safe_text(metadata, 8192),
                vec,
            ))
            ok += 1
        except Exception as ex:
            fail += 1
            if args.verbose:
                print(f"[text][skip] id={row_id}, reason={ex}")

        if len(batch_rows) >= args.batch_size:
            flush_text_batch(text_collection, batch_rows)
            batch_rows.clear()
            print(f"[text] progress total={total}, ok={ok}, fail={fail}")

    if batch_rows:
        flush_text_batch(text_collection, batch_rows)
        batch_rows.clear()

    text_collection.flush()
    text_collection.load()
    cur.close()
    print(f"[text] done total={total}, ok={ok}, fail={fail}, collection={args.text_collection}")


def flush_text_batch(collection: Collection, rows: List[Tuple[str, str, str, str, str, List[float]]]):
    ids = [x[0] for x in rows]
    kb_ids = [x[1] for x in rows]
    doc_ids = [x[2] for x in rows]
    contents = [x[3] for x in rows]
    metadatas = [x[4] for x in rows]
    vectors = [x[5] for x in rows]
    collection.insert([ids, kb_ids, doc_ids, contents, metadatas, vectors])


def rebuild_image(conn, image_collection: Collection, args):
    cur = conn.cursor()
    cur.execute(
        """
        SELECT ie.id::text,
               ie.kb_id::text,
               ie.doc_id::text,
               ie.file_name,
               ie.file_path,
               ie.metadata::text,
               d.metadata::text AS doc_metadata
        FROM image_embedding ie
        LEFT JOIN document d ON d.id = ie.doc_id
        ORDER BY ie.created_at ASC
        """
    )

    total = 0
    ok = 0
    fail = 0
    batch_rows: List[Tuple[str, str, str, str, str, str, List[float]]] = []
    docs_root = Path(args.docs_root).resolve()

    for row in cur:
        total += 1
        row_id, kb_id, doc_id, file_name, file_path, metadata, doc_metadata = row
        rel_path = file_path
        if not rel_path:
            rel_path = parse_document_file_path(doc_metadata)
        if not rel_path:
            fail += 1
            if args.verbose:
                print(f"[image][skip] id={row_id}, reason=no file_path")
            continue

        local_path = (docs_root / rel_path).resolve()
        if not local_path.exists() or not local_path.is_file():
            fail += 1
            if args.verbose:
                print(f"[image][skip] id={row_id}, path={local_path}, reason=file missing")
            continue

        try:
            image_bytes = local_path.read_bytes()
            vec = embed_image(args.image_embed_base_url, args.image_embed_model, image_bytes, args.timeout_sec)
            if len(vec) != args.image_dim:
                raise RuntimeError(f"dim mismatch: got={len(vec)}, expected={args.image_dim}")
            batch_rows.append((
                safe_text(row_id, 64),
                safe_text(kb_id, 64),
                safe_text(doc_id, 64),
                safe_text(file_name, 512),
                safe_text(rel_path, 2048),
                safe_text(metadata, 8192),
                vec,
            ))
            ok += 1
        except Exception as ex:
            fail += 1
            if args.verbose:
                print(f"[image][skip] id={row_id}, reason={ex}")

        if len(batch_rows) >= args.batch_size:
            flush_image_batch(image_collection, batch_rows)
            batch_rows.clear()
            print(f"[image] progress total={total}, ok={ok}, fail={fail}")

    if batch_rows:
        flush_image_batch(image_collection, batch_rows)
        batch_rows.clear()

    image_collection.flush()
    image_collection.load()
    cur.close()
    print(f"[image] done total={total}, ok={ok}, fail={fail}, collection={args.image_collection}")


def flush_image_batch(collection: Collection, rows: List[Tuple[str, str, str, str, str, str, List[float]]]):
    ids = [x[0] for x in rows]
    kb_ids = [x[1] for x in rows]
    doc_ids = [x[2] for x in rows]
    file_names = [x[3] for x in rows]
    file_paths = [x[4] for x in rows]
    metadatas = [x[5] for x in rows]
    vectors = [x[6] for x in rows]
    collection.insert([ids, kb_ids, doc_ids, file_names, file_paths, metadatas, vectors])


def main():
    p = argparse.ArgumentParser(
        description="Rebuild text/image embeddings from content/files and upsert to Milvus."
    )
    p.add_argument("--pg-host", default="localhost")
    p.add_argument("--pg-port", type=int, default=5432)
    p.add_argument("--pg-db", default="jchatmind")
    p.add_argument("--pg-user", default="postgres")
    p.add_argument("--pg-password", default="123456")

    p.add_argument("--milvus-host", default="localhost")
    p.add_argument("--milvus-port", default="19530")
    p.add_argument("--text-collection", default="chunk_bge_m3")
    p.add_argument("--image-collection", default="image_embedding")
    p.add_argument("--text-dim", type=int, default=1024)
    p.add_argument("--image-dim", type=int, default=512)

    p.add_argument("--text-embed-base-url", default="http://localhost:11434")
    p.add_argument("--text-embed-model", default="bge-m3")
    p.add_argument("--image-embed-base-url", default="http://localhost:18000")
    p.add_argument("--image-embed-model", default="OFA-Sys/chinese-clip-vit-base-patch16")

    p.add_argument("--docs-root", default="jchatmind/data/documents")
    p.add_argument("--mode", choices=["text", "image", "all"], default="all")
    p.add_argument("--batch-size", type=int, default=32)
    p.add_argument("--timeout-sec", type=int, default=30)
    p.add_argument("--recreate", action="store_true")
    p.add_argument("--verbose", action="store_true")
    args = p.parse_args()

    connections.connect(alias="default", host=args.milvus_host, port=args.milvus_port)
    conn = psycopg2.connect(
        host=args.pg_host,
        port=args.pg_port,
        dbname=args.pg_db,
        user=args.pg_user,
        password=args.pg_password,
    )

    try:
        if args.mode in ("text", "all"):
            text_collection = ensure_text_collection(args.text_collection, args.text_dim, args.recreate)
            rebuild_text(conn, text_collection, args)

        if args.mode in ("image", "all"):
            image_collection = ensure_image_collection(args.image_collection, args.image_dim, args.recreate)
            rebuild_image(conn, image_collection, args)
    finally:
        conn.close()
        connections.disconnect("default")


if __name__ == "__main__":
    main()

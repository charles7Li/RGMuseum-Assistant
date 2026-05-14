import argparse
import math
from dataclasses import dataclass
from typing import Any, Iterable, List, Optional

import psycopg2
from pymilvus import (
    Collection,
    CollectionSchema,
    DataType,
    FieldSchema,
    connections,
    utility,
)


def parse_pgvector(value) -> List[float]:
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        return [float(x) for x in value]
    s = str(value).strip()
    if not s:
        return []
    if s[0] == "[" and s[-1] == "]":
        s = s[1:-1]
    if not s:
        return []
    return [float(x) for x in s.split(",")]


def is_valid_vector(vec: List[float], dim: int) -> bool:
    return len(vec) == dim and all(math.isfinite(x) for x in vec)


def safe_text(value: Any, max_len: int) -> str:
    if value is None:
        return ""
    text = str(value)
    if len(text) > max_len:
        return text[:max_len]
    return text


def batched(rows: Iterable[Any], batch_size: int):
    batch = []
    for row in rows:
        batch.append(row)
        if len(batch) >= batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


@dataclass
class ImportJob:
    label: str
    collection_name: str
    dim: int
    sql: str
    fields: List[FieldSchema]
    text_max_len: int
    metadata_max_len: int


def ensure_collection(name: str, fields: List[FieldSchema], recreate: bool, description: str):
    if utility.has_collection(name):
        if recreate:
            utility.drop_collection(name)
        else:
            return Collection(name=name)

    schema = CollectionSchema(fields=fields, description=description)
    col = Collection(name=name, schema=schema)
    col.create_index(
        field_name="embedding",
        index_params={"index_type": "IVF_FLAT", "metric_type": "L2", "params": {"nlist": 1024}},
    )
    return col


def text_job(collection_name: str, dim: int) -> ImportJob:
    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, max_length=64, is_primary=True, auto_id=False),
        FieldSchema(name="kb_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="doc_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=16384),
        FieldSchema(name="metadata", dtype=DataType.VARCHAR, max_length=8192),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dim),
    ]
    return ImportJob(
        label="text",
        collection_name=collection_name,
        dim=dim,
        sql="""
            SELECT id::text, kb_id::text, doc_id::text, content, metadata::text, embedding
            FROM chunk_bge_m3
            WHERE embedding IS NOT NULL
        """,
        fields=fields,
        text_max_len=16384,
        metadata_max_len=8192,
    )


def image_job(collection_name: str, dim: int) -> ImportJob:
    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, max_length=64, is_primary=True, auto_id=False),
        FieldSchema(name="kb_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="doc_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="file_name", dtype=DataType.VARCHAR, max_length=512),
        FieldSchema(name="file_path", dtype=DataType.VARCHAR, max_length=2048),
        FieldSchema(name="metadata", dtype=DataType.VARCHAR, max_length=8192),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dim),
    ]
    return ImportJob(
        label="image",
        collection_name=collection_name,
        dim=dim,
        sql="""
            SELECT id::text, kb_id::text, doc_id::text, file_name, file_path, metadata::text, embedding
            FROM image_embedding
            WHERE embedding IS NOT NULL
        """,
        fields=fields,
        text_max_len=2048,
        metadata_max_len=8192,
    )


def import_text(conn, collection: Collection, job: ImportJob, batch_size: int):
    cur = conn.cursor()
    cur.execute(job.sql)

    total = 0
    accepted = 0
    skipped = 0

    def row_iter():
        nonlocal total, accepted, skipped
        for row in cur:
            total += 1
            row_id, kb_id, doc_id, content, metadata, embedding = row
            vec = parse_pgvector(embedding)
            if not is_valid_vector(vec, job.dim):
                skipped += 1
                continue
            accepted += 1
            yield (
                safe_text(row_id, 64),
                safe_text(kb_id, 64),
                safe_text(doc_id, 64),
                safe_text(content, job.text_max_len),
                safe_text(metadata, job.metadata_max_len),
                vec,
            )

    for batch in batched(row_iter(), batch_size):
        ids = [x[0] for x in batch]
        kb_ids = [x[1] for x in batch]
        doc_ids = [x[2] for x in batch]
        contents = [x[3] for x in batch]
        metadatas = [x[4] for x in batch]
        vectors = [x[5] for x in batch]
        collection.insert([ids, kb_ids, doc_ids, contents, metadatas, vectors])

    collection.flush()
    collection.load()
    cur.close()
    print(
        f"[{job.label}] done. total={total}, accepted={accepted}, skipped={skipped}, "
        f"collection={job.collection_name}, dim={job.dim}"
    )


def import_image(conn, collection: Collection, job: ImportJob, batch_size: int):
    cur = conn.cursor()
    cur.execute(job.sql)

    total = 0
    accepted = 0
    skipped = 0

    def row_iter():
        nonlocal total, accepted, skipped
        for row in cur:
            total += 1
            row_id, kb_id, doc_id, file_name, file_path, metadata, embedding = row
            vec = parse_pgvector(embedding)
            if not is_valid_vector(vec, job.dim):
                skipped += 1
                continue
            accepted += 1
            yield (
                safe_text(row_id, 64),
                safe_text(kb_id, 64),
                safe_text(doc_id, 64),
                safe_text(file_name, 512),
                safe_text(file_path, 2048),
                safe_text(metadata, job.metadata_max_len),
                vec,
            )

    for batch in batched(row_iter(), batch_size):
        ids = [x[0] for x in batch]
        kb_ids = [x[1] for x in batch]
        doc_ids = [x[2] for x in batch]
        file_names = [x[3] for x in batch]
        file_paths = [x[4] for x in batch]
        metadatas = [x[5] for x in batch]
        vectors = [x[6] for x in batch]
        collection.insert([ids, kb_ids, doc_ids, file_names, file_paths, metadatas, vectors])

    collection.flush()
    collection.load()
    cur.close()
    print(
        f"[{job.label}] done. total={total}, accepted={accepted}, skipped={skipped}, "
        f"collection={job.collection_name}, dim={job.dim}"
    )


def main():
    parser = argparse.ArgumentParser(
        description="Import text/image embeddings (+business fields) from PostgreSQL to Milvus"
    )
    parser.add_argument("--pg-host", default="localhost")
    parser.add_argument("--pg-port", type=int, default=5432)
    parser.add_argument("--pg-db", default="jchatmind")
    parser.add_argument("--pg-user", default="postgres")
    parser.add_argument("--pg-password", default="123456")
    parser.add_argument("--milvus-host", default="localhost")
    parser.add_argument("--milvus-port", default="19530")
    parser.add_argument("--mode", choices=["text", "image", "all"], default="all")
    parser.add_argument("--text-collection", default="chunk_bge_m3")
    parser.add_argument("--image-collection", default="image_embedding")
    parser.add_argument("--text-dim", type=int, default=1024)
    parser.add_argument("--image-dim", type=int, default=512)
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument("--recreate", action="store_true")
    args = parser.parse_args()

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
            job = text_job(args.text_collection, args.text_dim)
            collection = ensure_collection(
                job.collection_name, job.fields, args.recreate, "text chunks + embedding from PostgreSQL"
            )
            import_text(conn, collection, job, args.batch_size)

        if args.mode in ("image", "all"):
            job = image_job(args.image_collection, args.image_dim)
            collection = ensure_collection(
                job.collection_name, job.fields, args.recreate, "images + embedding from PostgreSQL"
            )
            import_image(conn, collection, job, args.batch_size)
    finally:
        conn.close()
        connections.disconnect("default")


if __name__ == "__main__":
    main()

-- 停用 PostgreSQL embedding 存储，仅保留 Milvus 向量检索
-- 执行前请先备份数据库

BEGIN;

-- 1) 停用 pgvector 索引（后续不再在 PG 内做向量检索）
DROP INDEX IF EXISTS idx_chunk_embedding;
DROP INDEX IF EXISTS idx_image_embedding;

-- 2) 允许 embedding 为空（后续新数据不再写入该列）
ALTER TABLE chunk_bge_m3
    ALTER COLUMN embedding DROP NOT NULL;

ALTER TABLE image_embedding
    ALTER COLUMN embedding DROP NOT NULL;

-- 3) 清空历史向量，确保“只存 Milvus”
UPDATE chunk_bge_m3
SET embedding = NULL
WHERE embedding IS NOT NULL;

UPDATE image_embedding
SET embedding = NULL
WHERE embedding IS NOT NULL;

COMMIT;

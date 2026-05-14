-- 迁移 image_embedding 向量维度到 512（CN-CLIP）
-- 适用于目前报错：expected 1024 dimensions, not 512

BEGIN;

-- 若历史数据不重要，建议先清空，避免维度转换失败
TRUNCATE TABLE image_embedding;

DROP INDEX IF EXISTS idx_image_embedding;

ALTER TABLE image_embedding
    ALTER COLUMN embedding TYPE vector(512);

CREATE INDEX idx_image_embedding
    ON image_embedding
    USING ivfflat (embedding vector_l2_ops)
    WITH (lists = 100);

COMMIT;

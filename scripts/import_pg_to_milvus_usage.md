# import_pg_to_milvus.py 使用说明

该脚本用于将 PostgreSQL 中的向量迁移到 Milvus，支持：

- 文本向量：`chunk_bge_m3.embedding`（默认 1024 维）
- 图片向量：`image_embedding.embedding`（默认 512 维）
- 并同步迁移业务字段（用于 Milvus 直接返回，不再回查 PG）
  - 文本：`id/kb_id/doc_id/content/metadata`
  - 图片：`id/kb_id/doc_id/file_name/file_path/metadata`

## 1) 同时迁移文本+图片（推荐）

```powershell
python scripts/import_pg_to_milvus.py `
  --mode all `
  --pg-host localhost --pg-port 5432 --pg-db jchatmind --pg-user postgres --pg-password 123456 `
  --milvus-host localhost --milvus-port 19530 `
  --text-collection chunk_bge_m3 `
  --image-collection image_embedding `
  --text-dim 1024 --image-dim 512 `
  --batch-size 512
```

## 2) 仅迁移文本向量

```powershell
python scripts/import_pg_to_milvus.py --mode text
```

## 3) 仅迁移图片向量

```powershell
python scripts/import_pg_to_milvus.py --mode image
```

## 4) 重建集合（全量重导）

首次建库或需要覆盖导入时，增加 `--recreate`：

```powershell
python scripts/import_pg_to_milvus.py --mode all --recreate
```

## 5) 关键参数说明

- `--mode`：`text | image | all`
- `--text-collection`：文本向量目标集合名，默认 `chunk_bge_m3`
- `--image-collection`：图片向量目标集合名，默认 `image_embedding`
- `--text-dim`：文本向量维度，默认 `1024`
- `--image-dim`：图片向量维度，默认 `512`
- `--batch-size`：批量写入大小，默认 `512`

## 6) 迁移结果输出

脚本会按类型输出统计信息，例如：

```text
[text] done. total=12000, accepted=11990, skipped=10, collection=chunk_bge_m3, dim=1024
[image] done. total=3000, accepted=2997, skipped=3, collection=image_embedding, dim=512
```

其中 `skipped` 表示向量为空、维度不匹配或存在非数值（NaN/Inf）的记录。

## 7) 注意事项

- 如果你已将 PostgreSQL 的 `embedding` 清空，本脚本会导入 0 条（因为它依赖 PG 中现有向量）。
- 这种情况下请走“重新向量化并 upsert 到 Milvus”的流程。

## 8) 重建向量脚本（PG embedding 已清空时）

脚本：`scripts/rebuild_embeddings_to_milvus.py`

用途：从文本内容和图片文件重新计算 embedding，并写入 Milvus（不依赖 PG 的 embedding 列）。

```powershell
python scripts/rebuild_embeddings_to_milvus.py `
  --mode all `
  --recreate `
  --pg-host localhost --pg-port 5432 --pg-db jchatmind --pg-user postgres --pg-password 123456 `
  --milvus-host localhost --milvus-port 19530 `
  --text-embed-base-url http://localhost:11434 --text-embed-model bge-m3 `
  --image-embed-base-url http://localhost:18000 --image-embed-model OFA-Sys/chinese-clip-vit-base-patch16 `
  --docs-root jchatmind/data/documents `
  --batch-size 32
```

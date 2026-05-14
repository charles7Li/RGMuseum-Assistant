# 国家博物馆结构化数据

目录说明：

- `manifest.json`：数据集说明
- `data/<artifact_slug>/info.json`：单件文物的结构化信息

说明：

1. 本地 Typora 图片路径没有被自动转成真实图片文件。
2. 若要接 CN-CLIP，请把每件文物的原图补到对应目录，例如：
   - `data/hezun/hezun.jpg`
   - `data/siyangfangzun/siyangfangzun.jpg`
3. 当前 `info.json` 已包含：
   - 基础字段
   - 文本描述
   - 图像描述
   - 关键词
   - 原始图片引用信息

推荐后续扩展字段：

- `artifact_id`
- `dynasty_standardized`
- `museum_standardized`
- `image_filename`
- `image_embedding_id`
- `text_embedding_id`

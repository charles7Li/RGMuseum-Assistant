# Museum Image Eval Dataset

本目录用于按「一文物一目录」组织图片检索评测集。

## 目录结构

```text
data/museum_eval/
  hezun/
    info.json
    hezun.jpg
  siyangfangzun/
    info.json
    siyangfangzun.jpg
  baiyu_kuilongpei/
    info.json
    baiyu_kuilongpei.jpg
```

## info.json 字段说明

- `id`: 样本ID（推荐与目录同名）
- `display_name`: 展示名（中文）
- `queries`: 该样本的测试查询列表（建议 2-5 条）
- `positives`: 期望命中的图片文件名列表（可多个）
- `aliases`: 别名（可选）

示例：

```json
{
  "id": "siyangfangzun",
  "display_name": "四羊方尊",
  "queries": [
    "四羊方尊",
    "商代四角羊首青铜方尊"
  ],
  "positives": [
    "siyangfangzun.jpg"
  ],
  "aliases": [
    "商代四羊方尊"
  ]
}
```

## 运行评测

```powershell
cd C:\Code\github\JChatMind
.\scripts\eval_image_dataset.ps1 `
  -BaseUrl "http://localhost:8080/api" `
  -KbId "替换成你的kbId" `
  -DatasetRoot "C:\Code\github\JChatMind\data\museum_eval" `
  -TopK 5 `
  -OutDetailCsv "C:\Code\github\JChatMind\data\museum_eval\image_eval_detail.csv" `
  -OutSummaryCsv "C:\Code\github\JChatMind\data\museum_eval\image_eval_summary.csv"
```


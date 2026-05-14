# Scripts

这个目录放的是项目里常用的自动化脚本。  
如果你的目标是“本地批量图文导入知识库”，优先看下面这几个。

## 批量导入流程

### 1. 生成标准文物数据集

脚本：
- `build_museum_artifact_dataset.ps1`

作用：
- 从原始文物素材中整理出统一目录结构
- 为每个文物生成 `info.json`
- 自动生成 `qa_pairs`
- 尽量匹配对应图片

常见输出结构：
```text
data/datasets/museum/data/artifact_dataset/
  artifact_001/
    info.json
    xxx.jpg
  artifact_002/
    info.json
    xxx.jpg
```

常用命令：
```powershell
powershell -ExecutionPolicy Bypass -File C:\Code\github\JChatMind\scripts\build_museum_artifact_dataset.ps1 -QaPairCount 6 -RandomizeQa
```

### 2. 导入到知识库

脚本：
- `import_artifact_dataset.ps1`

作用：
- 读取上一步生成的 `artifact_###/info.json`
- 自动转成 Markdown
- 上传 Markdown 文档到后端知识库
- 上传文物图片到图像检索库

常用命令：
```powershell
powershell -ExecutionPolicy Bypass -File C:\Code\github\JChatMind\scripts\import_artifact_dataset.ps1 `
  -BaseUrl http://localhost:8080/api `
  -DatasetRoot C:\Code\github\JChatMind\data\datasets\museum\data\artifact_dataset `
  -EmbeddingRule title+content(500)
```

推荐的 embedding 规则：
- `title+content(500)`

原因：
- 标题和正文都会参与检索
- 更适合文物介绍、特点、价值这类问题

## 常用辅助脚本

### `reimport_kb.ps1`

作用：
- 重新导入某个知识库里的文档
- 适合做知识库重灌或修复

### `ragas_eval.py`

作用：
- 跑 RAGAS 评测
- 读取问答样本
- 调后端生成回答并收集检索上下文
- 输出 `ragas_detail.csv` 和 `ragas_summary.json`

### `ragas_eval.ps1`

作用：
- `ragas_eval.py` 的 PowerShell 包装脚本

### `import_pg_to_milvus.py`

作用：
- 从 PostgreSQL 读取 `chunk_bge_m3`
- 导入到 Milvus

## 前端手工上传

如果不想批量导入，只是临时上传少量文档，可以在前端知识库页面里使用“上传文档”功能。  
但它更适合单个 Markdown 文件，不适合大批量图文数据。

## 说明

- 批量图文数据建议优先走脚本，不要手工在前端逐个上传
- 图片如果要被成功入库，Markdown 里的图片路径必须是后端能访问到的路径，或者是 URL / base64
- 生成数据集和导入知识库是两步，不要混在一起

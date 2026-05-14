# Local Python AI Service

Single service for:
- `POST /rerank`
- `POST /embed-text`
- `POST /embed-image`

## Setup

```powershell
cd C:\Code\github\JChatMind\rerank_service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install --upgrade pip
pip install -r requirements.txt
```

For NVIDIA GPU (example nightly CUDA 12.8):

```powershell
pip install --pre torch torchvision torchaudio --index-url https://download.pytorch.org/whl/nightly/cu128
```

## Run

```powershell
cd C:\Code\github\JChatMind\rerank_service
.\.venv\Scripts\Activate.ps1
$env:RERANK_MODEL="BAAI/bge-reranker-v2-m3"
$env:RERANK_USE_4BIT="true"
$env:RERANK_BATCH_SIZE="2"
$env:RERANK_MAX_LENGTH="256"
$env:IMAGE_EMBED_MODEL="OFA-Sys/chinese-clip-vit-base-patch16"
uvicorn app:app --host 0.0.0.0 --port 18000
```

## Health

```powershell
Invoke-RestMethod -Method GET -Uri "http://localhost:18000/health" | ConvertTo-Json
```

## API Examples

Rerank:

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:18000/rerank" `
  -ContentType "application/json" `
  -Body '{"query":"what is life points","documents":["life points are reduced each hit","base-60 is used for time"],"top_n":2}' `
  | ConvertTo-Json -Depth 6
```

Embed text:

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:18000/embed-text" `
  -ContentType "application/json" `
  -Body '{"text":"red bus"}' `
  | ConvertTo-Json -Depth 4
```

Embed image:

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\image.jpg"))
Invoke-RestMethod -Method POST -Uri "http://localhost:18000/embed-image" `
  -ContentType "application/json" `
  -Body (@{ image = $b64 } | ConvertTo-Json -Depth 4) `
  | ConvertTo-Json -Depth 4
```

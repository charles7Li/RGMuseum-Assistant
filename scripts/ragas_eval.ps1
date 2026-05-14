param(
  [string]$ApiBase = "http://localhost:8080/api",
  [string]$AgentId = "",
  [string]$KbId = "",
  [int]$Limit = 5,
  [string]$JudgeModel = "qwen-plus",
  [string]$JudgeBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
  [string]$EmbeddingModel = "BAAI/bge-m3",
  [string]$EmbeddingDevice = "cuda:0",
  [string]$ContextSource = "agent",
  [string]$PreferredCondaEnv = "ragas311",
  [bool]$DisableImageTool = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($AgentId)) {
  throw "Please pass -AgentId"
}
if ([string]::IsNullOrWhiteSpace($KbId)) {
  throw "Please pass -KbId"
}

# DashScope key can be reused as OPENAI_API_KEY for OpenAI-compatible endpoint.
if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY) -and -not [string]::IsNullOrWhiteSpace($env:DASHSCOPE_API_KEY)) {
  $env:OPENAI_API_KEY = $env:DASHSCOPE_API_KEY
}

$scriptPath = Join-Path $PSScriptRoot "ragas_eval.py"

$pythonExe = "python"
try {
  $condaExe = (Get-Command conda -ErrorAction Stop).Source
  $condaBase = & $condaExe info --base 2>$null
  if (-not [string]::IsNullOrWhiteSpace($condaBase)) {
    $condaBase = $condaBase.Trim()
    $candidatePython = Join-Path $condaBase ("envs\" + $PreferredCondaEnv + "\python.exe")
    if (Test-Path $candidatePython) {
      $pythonExe = $candidatePython
    }
  }
} catch {
  # fallback to current shell python
}

Write-Host "Using Python: $pythonExe"

$argsList = @(
  $scriptPath,
  "--api-base", $ApiBase,
  "--agent-id", $AgentId,
  "--kb-id", $KbId,
  "--limit", "$Limit",
  "--context-source", $ContextSource,
  "--judge-model", $JudgeModel,
  "--judge-base-url", $JudgeBaseUrl,
  "--embedding-model", $EmbeddingModel,
  "--embedding-device", $EmbeddingDevice,
  "--allow-no-embeddings"
)

if ($DisableImageTool) {
  $argsList += "--disable-image-tool"
}

& $pythonExe @argsList

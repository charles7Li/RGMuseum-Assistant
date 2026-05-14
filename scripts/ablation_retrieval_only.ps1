param(
  [string]$BaseUrl = "http://localhost:8080/api",
  [string]$EvalCsv = "C:\Code\github\JChatMind\data\datasets\cmrc\cmrc2018_trial_eval.csv",
  [int]$Limit = 10,
  [int]$TopK = 3,
  [ValidateSet("answer-content","source-context")]
  [string]$HitMode = "answer-content",
  [string]$KbTitleOnly = "",
  [string]$KbTitleContent500 = "",
  [string]$KbContentOnly500 = "",
  [string]$OutDetailCsv = "C:\Code\github\JChatMind\data\datasets\cmrc\ablation_retrieval_detail.csv",
  [string]$OutSummaryCsv = "C:\Code\github\JChatMind\data\datasets\cmrc\ablation_retrieval_summary.csv"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ScriptVersion = "2026-03-30-r1"

if (!(Test-Path -LiteralPath $EvalCsv)) {
  throw "Eval CSV not found: $EvalCsv"
}
if ([string]::IsNullOrWhiteSpace($KbTitleOnly) -or
    [string]::IsNullOrWhiteSpace($KbTitleContent500) -or
    [string]::IsNullOrWhiteSpace($KbContentOnly500)) {
  throw "Please pass all three KB ids."
}

function Ensure-ParentDir {
  param([string]$Path)
  $dir = Split-Path -Parent $Path
  if (-not [string]::IsNullOrWhiteSpace($dir) -and -not (Test-Path -LiteralPath $dir)) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
  }
}

function Invoke-Api {
  param(
    [ValidateSet("GET","POST","PATCH","DELETE")]
    [string]$Method,
    [string]$Path,
    [object]$Body = $null
  )
  $url = "$BaseUrl$Path"
  try {
    $request = [System.Net.WebRequest]::Create($url)
    $request.Method = $Method.ToUpperInvariant()
    $request.Accept = "application/json"
    $request.ContentType = "application/json; charset=utf-8"
    if ($null -ne $Body) {
      $json = $Body | ConvertTo-Json -Depth 10
      $payload = [System.Text.Encoding]::UTF8.GetBytes($json)
      $request.ContentLength = $payload.Length
      $reqStream = $request.GetRequestStream()
      $reqStream.Write($payload, 0, $payload.Length)
      $reqStream.Close()
    }
    $response = $request.GetResponse()
    $stream = $response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $reader.Close()
    $stream.Close()
    $response.Close()
    return $text | ConvertFrom-Json
  } catch {
    throw "API $Method $url failed: $($_.Exception.Message)"
  }
}

function Normalize-Text {
  param([string]$Text)
  if ($null -eq $Text) { return "" }
  $x = $Text.ToLowerInvariant()
  $x = $x -replace "\s+", ""
  $x = $x -replace "[\p{P}\p{S}]", ""
  return $x
}

function Source-Hit {
  param(
    [string]$Source,
    [string]$ContextId
  )
  if ([string]::IsNullOrWhiteSpace($Source)) { return $false }
  if ([string]::IsNullOrWhiteSpace($ContextId)) { return $false }
  $s = Normalize-Text $Source
  $c = Normalize-Text $ContextId
  return $s.Contains($c)
}

function Answer-Hit {
  param(
    [string]$Content,
    [string]$GoldAnswers
  )
  if ([string]::IsNullOrWhiteSpace($Content)) { return $false }
  if ([string]::IsNullOrWhiteSpace($GoldAnswers)) { return $false }
  $c = Normalize-Text $Content
  $a = Normalize-Text $GoldAnswers
  if ([string]::IsNullOrWhiteSpace($c) -or [string]::IsNullOrWhiteSpace($a)) { return $false }
  return $c.Contains($a)
}

function Export-CsvUtf8Bom {
  param(
    [object]$Rows,
    [string]$Path
  )
  $arr = @($Rows)
  if ($Rows -is [System.Collections.IEnumerable] -and -not ($Rows -is [string])) {
    $arr = @($Rows | ForEach-Object { $_ })
  }
  $csvLines = @($arr | ConvertTo-Csv -NoTypeInformation)
  $utf8Bom = New-Object System.Text.UTF8Encoding($true)
  [System.IO.File]::WriteAllLines($Path, $csvLines, $utf8Bom)
}

$rows = @(Import-Csv -Path $EvalCsv -Encoding UTF8)
if ($Limit -gt 0 -and $Limit -lt $rows.Count) {
  $rows = @($rows | Select-Object -First $Limit)
}

$groups = @(
  [pscustomobject]@{ name = "title-only"; kbId = $KbTitleOnly },
  [pscustomobject]@{ name = "title+content(500)"; kbId = $KbTitleContent500 },
  [pscustomobject]@{ name = "content-only(500)"; kbId = $KbContentOnly500 }
)

Ensure-ParentDir -Path $OutDetailCsv
Ensure-ParentDir -Path $OutSummaryCsv

Write-Host "ScriptVersion: $ScriptVersion"
Write-Host "Rows: $($rows.Count)"
Write-Host "TopK: $TopK"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "HitMode: $HitMode"

$detailRows = New-Object System.Collections.Generic.List[object]
$summaryRows = New-Object System.Collections.Generic.List[object]

foreach ($g in $groups) {
  Write-Host ""
  Write-Host "=== Retrieval group: $($g.name) | kb=$($g.kbId) ==="
  $top1Count = 0
  $hit3Count = 0
  $errCount = 0
  $idx = 0
  foreach ($r in $rows) {
    $idx++
    $queryId = [string]$r.query_id
    $queryText = [string]$r.query_text
    $contextId = [string]$r.context_id
    $goldAnswers = [string]$r.gold_answers

    $source1 = ""
    $source2 = ""
    $source3 = ""
    $content1 = ""
    $content2 = ""
    $content3 = ""
    $top1 = $false
    $hit3 = $false
    $errMsg = ""

    try {
      $resp = Invoke-Api -Method "POST" -Path "/rag/retrieve" -Body @{
        kbId = $g.kbId
        query = $queryText
        topK = $TopK
      }
      if ($null -eq $resp -or $resp.code -ne 200 -or $null -eq $resp.data) {
        $raw = $resp | ConvertTo-Json -Depth 10 -Compress
        throw "Bad response: $raw"
      }
      $hits = @($resp.data.hits)
      if ($hits.Length -ge 1) { $source1 = [string]$hits[0].source }
      if ($hits.Length -ge 2) { $source2 = [string]$hits[1].source }
      if ($hits.Length -ge 3) { $source3 = [string]$hits[2].source }
      if ($hits.Length -ge 1) { $content1 = [string]$hits[0].content }
      if ($hits.Length -ge 2) { $content2 = [string]$hits[1].content }
      if ($hits.Length -ge 3) { $content3 = [string]$hits[2].content }

      if ($HitMode -eq "source-context") {
        $top1 = Source-Hit -Source $source1 -ContextId $contextId
        $hit3 = $top1 -or (Source-Hit -Source $source2 -ContextId $contextId) -or (Source-Hit -Source $source3 -ContextId $contextId)
      } else {
        $top1 = Answer-Hit -Content $content1 -GoldAnswers $goldAnswers
        $hit3 = $top1 -or (Answer-Hit -Content $content2 -GoldAnswers $goldAnswers) -or (Answer-Hit -Content $content3 -GoldAnswers $goldAnswers)
      }
    } catch {
      $errMsg = "$($_.Exception.Message) | stack: $($_.ScriptStackTrace)"
    }

    if ($top1) { $top1Count++ }
    if ($hit3) { $hit3Count++ }
    if (-not [string]::IsNullOrWhiteSpace($errMsg)) { $errCount++ }

    $detailRows.Add([pscustomobject]@{
      group_name = $g.name
      kb_id = $g.kbId
      query_id = $queryId
      query_text = $queryText
      context_id = $contextId
      top1 = $top1
      hit3 = $hit3
      source1 = $source1
      source2 = $source2
      source3 = $source3
      content1 = $content1
      content2 = $content2
      content3 = $content3
      error = $errMsg
    })

    Write-Host "[$($g.name)] [$idx/$($rows.Count)] $queryId => top1=$top1 hit3=$hit3"
  }

  $n = [Math]::Max(1, $rows.Count)
  $summaryRows.Add([pscustomobject]@{
    group_name = $g.name
    kb_id = $g.kbId
    total = $rows.Count
    top1_count = $top1Count
    top1_pct = [Math]::Round(($top1Count * 100.0) / $n, 2)
    hit3_count = $hit3Count
    hit3_pct = [Math]::Round(($hit3Count * 100.0) / $n, 2)
    error_count = $errCount
  })
}

Export-CsvUtf8Bom -Rows ($detailRows.ToArray()) -Path $OutDetailCsv
Export-CsvUtf8Bom -Rows ($summaryRows.ToArray()) -Path $OutSummaryCsv

Write-Host ""
Write-Host "Done."
Write-Host "Detail:  $OutDetailCsv"
Write-Host "Summary: $OutSummaryCsv"

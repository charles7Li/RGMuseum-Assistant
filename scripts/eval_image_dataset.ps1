param(
  [string]$BaseUrl = "http://localhost:8080/api",
  [string]$KbId = "",
  [string]$DatasetRoot = "C:\Code\github\JChatMind\data\museum_eval",
  [int]$TopK = 5,
  [string]$OutDetailCsv = "C:\Code\github\JChatMind\data\museum_eval\image_eval_detail.csv",
  [string]$OutSummaryCsv = "C:\Code\github\JChatMind\data\museum_eval\image_eval_summary.csv"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ScriptVersion = "2026-04-02-r1"

if ([string]::IsNullOrWhiteSpace($KbId)) {
  throw "Please pass -KbId."
}
if (!(Test-Path -LiteralPath $DatasetRoot)) {
  throw "Dataset root not found: $DatasetRoot"
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

function Normalize-Token {
  param([string]$Text)
  if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
  $x = $Text.ToLowerInvariant()
  $x = [System.IO.Path]::GetFileNameWithoutExtension($x)
  $x = $x -replace "\s+", ""
  $x = $x -replace "[\p{P}\p{S}]", ""
  return $x
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

function Read-InfoJson {
  param([string]$FolderPath)
  $infoPath = Join-Path $FolderPath "info.json"
  if (!(Test-Path -LiteralPath $infoPath)) {
    throw "Missing info.json: $infoPath"
  }
  $raw = Get-Content -LiteralPath $infoPath -Raw -Encoding UTF8
  if ([string]::IsNullOrWhiteSpace($raw)) {
    throw "Empty info.json: $infoPath"
  }
  return $raw | ConvertFrom-Json
}

function Get-CaseImageFiles {
  param([string]$FolderPath)
  $files = Get-ChildItem -LiteralPath $FolderPath -File |
    Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp|bmp)$' } |
    Select-Object -ExpandProperty Name
  return @($files)
}

function Build-ExpectedSet {
  param(
    [object]$Info,
    [string[]]$Images
  )
  $set = New-Object System.Collections.Generic.HashSet[string]

  if ($null -ne $Info.positives) {
    foreach ($p in @($Info.positives)) {
      $n = Normalize-Token ([string]$p)
      if (-not [string]::IsNullOrWhiteSpace($n)) { [void]$set.Add($n) }
    }
  }

  if ($set.Count -eq 0 -and $Images.Count -gt 0) {
    foreach ($img in $Images) {
      $n = Normalize-Token $img
      if (-not [string]::IsNullOrWhiteSpace($n)) { [void]$set.Add($n) }
    }
  }

  foreach ($field in @("id","display_name","name")) {
    if ($Info.PSObject.Properties.Name -contains $field) {
      $n = Normalize-Token ([string]$Info.$field)
      if (-not [string]::IsNullOrWhiteSpace($n)) { [void]$set.Add($n) }
    }
  }

  if ($Info.PSObject.Properties.Name -contains "aliases") {
    foreach ($a in @($Info.aliases)) {
      $n = Normalize-Token ([string]$a)
      if (-not [string]::IsNullOrWhiteSpace($n)) { [void]$set.Add($n) }
    }
  }

  return $set
}

function Is-ExpectedHit {
  param(
    [string]$FileName,
    [System.Collections.Generic.HashSet[string]]$ExpectedSet
  )
  $cand = Normalize-Token $FileName
  if ([string]::IsNullOrWhiteSpace($cand)) { return $false }
  if ($ExpectedSet.Contains($cand)) { return $true }
  foreach ($e in $ExpectedSet) {
    if ($cand.Contains($e) -or $e.Contains($cand)) { return $true }
  }
  return $false
}

Ensure-ParentDir -Path $OutDetailCsv
Ensure-ParentDir -Path $OutSummaryCsv

$caseFolders = @(Get-ChildItem -LiteralPath $DatasetRoot -Directory)
if ($caseFolders.Count -eq 0) {
  throw "No case folders found under $DatasetRoot"
}

Write-Host "ScriptVersion: $ScriptVersion"
Write-Host "KbId: $KbId"
Write-Host "DatasetRoot: $DatasetRoot"
Write-Host "CaseCount: $($caseFolders.Count)"
Write-Host "TopK: $TopK"

$detailRows = New-Object System.Collections.Generic.List[object]
$totalQueries = 0
$top1Count = 0
$hitKCount = 0
$errorCount = 0

foreach ($folder in $caseFolders) {
  $caseName = $folder.Name
  $folderPath = $folder.FullName
  $info = Read-InfoJson -FolderPath $folderPath
  $images = Get-CaseImageFiles -FolderPath $folderPath
  $expectedSet = Build-ExpectedSet -Info $info -Images $images

  $queries = @()
  if ($info.PSObject.Properties.Name -contains "queries") {
    $queries = @($info.queries | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
  }
  if ($queries.Count -eq 0 -and $info.PSObject.Properties.Name -contains "display_name") {
    $queries = @([string]$info.display_name)
  }
  if ($queries.Count -eq 0 -and $info.PSObject.Properties.Name -contains "name") {
    $queries = @([string]$info.name)
  }
  if ($queries.Count -eq 0) {
    throw "No usable query in $($folderPath)\info.json (need queries[] or display_name/name)."
  }

  foreach ($q in $queries) {
    $totalQueries++
    $queryText = [string]$q
    $returned = @()
    $top1 = $false
    $hitK = $false
    $err = ""
    try {
      $resp = Invoke-Api -Method "POST" -Path "/rag/images/retrieve" -Body @{
        kbId = $KbId
        query = $queryText
        topK = $TopK
      }
      if ($null -eq $resp -or $resp.code -ne 200 -or $null -eq $resp.data) {
        $raw = $resp | ConvertTo-Json -Depth 10 -Compress
        throw "Bad response: $raw"
      }
      $hits = @($resp.data.hits)
      $returned = @($hits | ForEach-Object { [string]$_.fileName })
      if ($returned.Count -ge 1) {
        $top1 = Is-ExpectedHit -FileName $returned[0] -ExpectedSet $expectedSet
      }
      $max = [Math]::Min($TopK, $returned.Count)
      for ($i = 0; $i -lt $max; $i++) {
        if (Is-ExpectedHit -FileName $returned[$i] -ExpectedSet $expectedSet) {
          $hitK = $true
          break
        }
      }
    } catch {
      $err = "$($_.Exception.Message)"
    }

    if ($top1) { $top1Count++ }
    if ($hitK) { $hitKCount++ }
    if (-not [string]::IsNullOrWhiteSpace($err)) { $errorCount++ }

    $detailRows.Add([pscustomobject]@{
      case_name = $caseName
      query = $queryText
      expected = (($expectedSet | ForEach-Object { $_ }) -join "|")
      top1 = $top1
      hit_topk = $hitK
      hit1_file = $(if ($returned.Count -ge 1) { $returned[0] } else { "" })
      hit2_file = $(if ($returned.Count -ge 2) { $returned[1] } else { "" })
      hit3_file = $(if ($returned.Count -ge 3) { $returned[2] } else { "" })
      error = $err
    })
  }
}

$den = [Math]::Max(1, $totalQueries)
$summaryRows = @(
  [pscustomobject]@{
    total_queries = $totalQueries
    top1_count = $top1Count
    top1_pct = [Math]::Round(($top1Count * 100.0) / $den, 2)
    hit_topk_count = $hitKCount
    hit_topk_pct = [Math]::Round(($hitKCount * 100.0) / $den, 2)
    error_count = $errorCount
    topk = $TopK
    dataset_root = $DatasetRoot
    kb_id = $KbId
  }
)

Export-CsvUtf8Bom -Rows ($detailRows.ToArray()) -Path $OutDetailCsv
Export-CsvUtf8Bom -Rows $summaryRows -Path $OutSummaryCsv

Write-Host ""
Write-Host "Done."
Write-Host "Detail : $OutDetailCsv"
Write-Host "Summary: $OutSummaryCsv"


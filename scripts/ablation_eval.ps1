param(
  [string]$BaseUrl = "http://localhost:8080/api",
  [string]$AgentId = "",
  [string]$AgentName = "",
  [string]$EvalCsv = "C:\Code\github\JChatMind\data\datasets\cmrc\cmrc2018_trial_eval.csv",
  [int]$Limit = 50,
  [int]$TimeoutSec = 45,
  [int]$PollMs = 800,
  [string]$KbTitleOnly = "",
  [string]$KbTitleContent500 = "",
  [string]$KbContentOnly500 = "",
  [string]$OutDetailCsv = "C:\Code\github\JChatMind\data\datasets\cmrc\ablation_detail.csv",
  [string]$OutSummaryCsv = "C:\Code\github\JChatMind\data\datasets\cmrc\ablation_summary.csv"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ScriptVersion = "2026-03-30-r3"

[Console]::InputEncoding  = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if ([string]::IsNullOrWhiteSpace($AgentId)) {
  if ([string]::IsNullOrWhiteSpace($AgentName)) {
    throw "Missing agent identity. Pass -AgentId or -AgentName."
  }
}
if (!(Test-Path -LiteralPath $EvalCsv)) {
  throw "Eval CSV not found: $EvalCsv"
}
if ([string]::IsNullOrWhiteSpace($KbTitleOnly) -or
    [string]::IsNullOrWhiteSpace($KbTitleContent500) -or
    [string]::IsNullOrWhiteSpace($KbContentOnly500)) {
  throw "Please pass all three KB ids: -KbTitleOnly -KbTitleContent500 -KbContentOnly500"
}

function Ensure-ParentDir {
  param([string]$Path)
  $dir = Split-Path -Parent $Path
  if (-not [string]::IsNullOrWhiteSpace($dir) -and -not (Test-Path -LiteralPath $dir)) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
  }
}

function Get-ExceptionText {
  param([System.Exception]$Exception)
  if ($null -eq $Exception) { return "Unknown error" }
  $msg = $Exception.Message
  try {
    if ($Exception.PSObject.Properties.Name -contains "Response" -and $null -ne $Exception.Response) {
      $stream = $Exception.Response.GetResponseStream()
      if ($null -ne $stream) {
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        $body = $reader.ReadToEnd()
        $reader.Close()
        $stream.Close()
        if (-not [string]::IsNullOrWhiteSpace($body)) {
          return "$msg | $body"
        }
      }
    }
  } catch {
    # ignore
  }
  return $msg
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
      $json = $Body | ConvertTo-Json -Depth 12
      $payload = [System.Text.Encoding]::UTF8.GetBytes($json)
      $request.ContentLength = $payload.Length
      $reqStream = $request.GetRequestStream()
      $reqStream.Write($payload, 0, $payload.Length)
      $reqStream.Close()
    }

    $response = $request.GetResponse()
    $respStream = $response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($respStream, [System.Text.Encoding]::UTF8)
    $respText = $reader.ReadToEnd()
    $reader.Close()
    $respStream.Close()
    $response.Close()

    return $respText | ConvertFrom-Json
  } catch {
    throw "API $Method $url failed: $(Get-ExceptionText $_.Exception)"
  }
}

function Get-Agents {
  $resp = Invoke-Api -Method "GET" -Path "/agents"
  if ($null -eq $resp -or $resp.code -ne 200 -or $null -eq $resp.data) {
    $raw = $resp | ConvertTo-Json -Depth 12 -Compress
    throw "Failed to query /agents, response: $raw"
  }
  return @($resp.data.agents)
}

function Resolve-AgentId {
  param(
    [string]$InputAgentId,
    [string]$InputAgentName
  )

  $agents = Get-Agents
  if ($agents.Count -eq 0) {
    throw "No agents found in current backend."
  }

  if (-not [string]::IsNullOrWhiteSpace($InputAgentId)) {
    $byId = @($agents | Where-Object { $_.id -eq $InputAgentId })
    if ($byId.Count -gt 0) {
      return [pscustomobject]@{
        agentId = $byId[0].id
        agentName = $byId[0].name
      }
    }
  }

  if (-not [string]::IsNullOrWhiteSpace($InputAgentName)) {
    $byName = @($agents | Where-Object { $_.name -eq $InputAgentName })
    if ($byName.Count -eq 1) {
      return [pscustomobject]@{
        agentId = $byName[0].id
        agentName = $byName[0].name
      }
    }
    if ($byName.Count -gt 1) {
      $candidates = ($byName | ForEach-Object { "$($_.name) [$($_.id)]" }) -join "; "
      throw "Multiple agents matched name '$InputAgentName': $candidates"
    }
  }

  $all = ($agents | ForEach-Object { "$($_.name) [$($_.id)]" }) -join "; "
  if (-not [string]::IsNullOrWhiteSpace($InputAgentId)) {
    throw "Agent not found in this backend: $InputAgentId. Available agents: $all"
  }
  throw "Agent name not found in this backend: $InputAgentName. Available agents: $all"
}

function Normalize-Text {
  param([string]$Text)
  if ($null -eq $Text) { return "" }
  $x = $Text.ToLowerInvariant()
  $x = $x -replace "\s+", ""
  $x = $x -replace "[\p{P}\p{S}]", ""
  return $x
}

function Is-AnswerHit {
  param(
    [string]$Prediction,
    [string[]]$GoldAnswers
  )
  $predNorm = Normalize-Text $Prediction
  if ([string]::IsNullOrWhiteSpace($predNorm)) { return $false }
  foreach ($gold in $GoldAnswers) {
    $goldNorm = Normalize-Text $gold
    if ([string]::IsNullOrWhiteSpace($goldNorm)) { continue }
    if ($predNorm.Contains($goldNorm) -or $goldNorm.Contains($predNorm)) {
      return $true
    }
  }
  return $false
}

function Extract-Sources {
  param([string]$ToolContent)
  $result = New-Object System.Collections.Generic.List[string]
  if ([string]::IsNullOrWhiteSpace($ToolContent)) {
    return [string[]]@()
  }

  $regex = [regex]"(?im)^\s*Source:\s*(.+?)\s*$"
  $matches = $regex.Matches($ToolContent)
  foreach ($m in $matches) {
    $name = $m.Groups[1].Value.Trim()
    if (-not [string]::IsNullOrWhiteSpace($name)) {
      $result.Add($name)
    }
  }

  if ($result.Count -eq 0) {
    $regexFallback = [regex]"TRIAL_\d+"
    $fallback = $regexFallback.Matches($ToolContent) | ForEach-Object { $_.Value } | Select-Object -Unique
    foreach ($f in $fallback) {
      $result.Add($f)
    }
  }
  return $result.ToArray()
}

function Evaluate-SourceHit {
  param(
    [string[]]$Sources,
    [string]$ContextId,
    [string]$Title
  )
  $src = @()
  if ($null -ne $Sources) {
    $src = @($Sources | Where-Object { $null -ne $_ -and -not [string]::IsNullOrWhiteSpace([string]$_) })
  }
  $srcLen = @($src).Length
  $top1 = $false
  $hit3 = $false
  $source1 = ""
  $source2 = ""
  $source3 = ""

  if ($srcLen -ge 1) { $source1 = [string]$src[0] }
  if ($srcLen -ge 2) { $source2 = [string]$src[1] }
  if ($srcLen -ge 3) { $source3 = [string]$src[2] }

  $ctx = Normalize-Text $ContextId
  $ttl = Normalize-Text $Title

  $matchFn = {
    param([string]$s)
    if ([string]::IsNullOrWhiteSpace($s)) { return $false }
    $sn = Normalize-Text $s
    if (-not [string]::IsNullOrWhiteSpace($ctx) -and $sn.Contains($ctx)) { return $true }
    if (-not [string]::IsNullOrWhiteSpace($ttl) -and $sn.Contains($ttl)) { return $true }
    return $false
  }

  if ($srcLen -ge 1 -and (& $matchFn ([string]$src[0]))) { $top1 = $true }
  $max = [Math]::Min(3, $srcLen)
  for ($i = 0; $i -lt $max; $i++) {
    if (& $matchFn ([string]$src[$i])) {
      $hit3 = $true
      break
    }
  }

  return [pscustomobject]@{
    top1 = $top1
    hit3 = $hit3
    source1 = $source1
    source2 = $source2
    source3 = $source3
  }
}

function Export-CsvUtf8Bom {
  param(
    [object]$Rows,
    [string]$Path
  )
  $rowArray = @($Rows)
  if ($Rows -is [System.Collections.IEnumerable] -and -not ($Rows -is [string])) {
    $rowArray = @($Rows | ForEach-Object { $_ })
  }
  $csvLines = @($rowArray | ConvertTo-Csv -NoTypeInformation)
  $utf8Bom = New-Object System.Text.UTF8Encoding($true)
  [System.IO.File]::WriteAllLines($Path, $csvLines, $utf8Bom)
}

function Set-AgentKb {
  param(
    [string]$AgentIdForPatch,
    [string]$KnowledgeBaseId
  )
  $resp = Invoke-Api -Method "PATCH" -Path "/agents/$AgentIdForPatch" -Body @{
    allowedKbs = @($KnowledgeBaseId)
  }
  if ($null -eq $resp -or $resp.code -ne 200) {
    $raw = $resp | ConvertTo-Json -Depth 12 -Compress
    $agentHint = ""
    try {
      $agents = Get-Agents
      if ($agents.Count -gt 0) {
        $agentHint = ($agents | ForEach-Object { "$($_.name) [$($_.id)]" }) -join "; "
      }
    } catch {
      # ignore hint failures
    }
    if (-not [string]::IsNullOrWhiteSpace($agentHint)) {
      throw "Failed to patch agent allowedKbs, response: $raw | Available agents: $agentHint"
    }
    throw "Failed to patch agent allowedKbs, response: $raw"
  }
}

function Create-Session {
  param(
    [string]$AgentIdForSession,
    [string]$Title
  )
  $resp = Invoke-Api -Method "POST" -Path "/chat-sessions" -Body @{
    agentId = $AgentIdForSession
    title   = $Title
  }
  if ($null -eq $resp -or $resp.code -ne 200 -or $null -eq $resp.data -or [string]::IsNullOrWhiteSpace($resp.data.chatSessionId)) {
    $raw = $resp | ConvertTo-Json -Depth 12 -Compress
    throw "Failed to create session, response: $raw"
  }
  return $resp.data.chatSessionId
}

function Send-UserMessage {
  param(
    [string]$AgentIdForMsg,
    [string]$SessionId,
    [string]$Prompt
  )
  $resp = Invoke-Api -Method "POST" -Path "/chat-messages" -Body @{
    agentId   = $AgentIdForMsg
    sessionId = $SessionId
    role      = "user"
    content   = $Prompt
  }
  if ($null -eq $resp -or $resp.code -ne 200) {
    $raw = $resp | ConvertTo-Json -Depth 12 -Compress
    throw "Failed to send user message, response: $raw"
  }
}

function Wait-ForResult {
  param(
    [string]$SessionId,
    [int]$TimeoutSeconds,
    [int]$PollIntervalMs
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  $assistantText = ""
  $toolText = ""
  $timedOut = $true

  do {
    Start-Sleep -Milliseconds $PollIntervalMs
    $resp = Invoke-Api -Method "GET" -Path "/chat-messages/session/$SessionId"
    if ($null -eq $resp -or $resp.code -ne 200 -or $null -eq $resp.data) {
      continue
    }
    $msgs = @($resp.data.chatMessages)
    if ($msgs.Count -eq 0) {
      continue
    }

    $assistants = @($msgs | Where-Object { $_.role -eq "assistant" -and -not [string]::IsNullOrWhiteSpace([string]$_.content) })
    if ($assistants.Count -gt 0) {
      $assistantText = [string]$assistants[-1].content
    }

    $tools = @($msgs | Where-Object { $_.role -eq "tool" -and -not [string]::IsNullOrWhiteSpace([string]$_.content) })
    if ($tools.Count -gt 0) {
      $toolText = [string]$tools[-1].content
    }

    if (-not [string]::IsNullOrWhiteSpace($assistantText)) {
      $timedOut = $false
      break
    }
  } while ((Get-Date) -lt $deadline)

  return [pscustomobject]@{
    assistant = $assistantText
    tool = $toolText
    timeout = $timedOut
  }
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

Write-Host "Rows: $($rows.Count)"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "ScriptVersion: $ScriptVersion"

$resolved = Resolve-AgentId -InputAgentId $AgentId -InputAgentName $AgentName
$AgentId = [string]$resolved.agentId
$resolvedName = [string]$resolved.agentName
Write-Host "AgentId: $AgentId"
Write-Host "AgentName: $resolvedName"

$detailRows = New-Object System.Collections.Generic.List[object]
$summaryRows = New-Object System.Collections.Generic.List[object]

foreach ($g in $groups) {
  Write-Host ""
  Write-Host "=== Running group: $($g.name) | kb=$($g.kbId) ==="

  Set-AgentKb -AgentIdForPatch $AgentId -KnowledgeBaseId $g.kbId

  $top1Count = 0
  $hit3Count = 0
  $answerHitCount = 0
  $errorCount = 0
  $idx = 0

  foreach ($r in $rows) {
    $idx++
    $queryId = [string]$r.query_id
    $queryText = [string]$r.query_text
    $goldAnswers = @()
    if (-not [string]::IsNullOrWhiteSpace([string]$r.gold_answers)) {
      $goldAnswers = [string]$r.gold_answers -split "\|\|"
    }
    $contextId = [string]$r.context_id
    $title = [string]$r.title

    Write-Host "[$($g.name)] [$idx/$($rows.Count)] $queryId"

    $sessionId = ""
    $assistantText = ""
    $toolText = ""
    $errorText = ""
    $timeout = $false
    $top1 = $false
    $hit3 = $false
    $answerHit = $false
    $source1 = ""
    $source2 = ""
    $source3 = ""

    try {
      $sessionTitle = "ablation_$($g.name)_$queryId"
      $sessionId = Create-Session -AgentIdForSession $AgentId -Title $sessionTitle
      Send-UserMessage -AgentIdForMsg $AgentId -SessionId $sessionId -Prompt $queryText

      $wait = Wait-ForResult -SessionId $sessionId -TimeoutSeconds $TimeoutSec -PollIntervalMs $PollMs
      $assistantText = [string]$wait.assistant
      $toolText = [string]$wait.tool
      $timeout = [bool]$wait.timeout
      if ($timeout) {
        $errorText = "timeout_waiting_assistant"
      }

      $answerHit = Is-AnswerHit -Prediction $assistantText -GoldAnswers $goldAnswers
      $sources = Extract-Sources -ToolContent $toolText
      $eval = Evaluate-SourceHit -Sources $sources -ContextId $contextId -Title $title
      $top1 = [bool]$eval.top1
      $hit3 = [bool]$eval.hit3
      $source1 = [string]$eval.source1
      $source2 = [string]$eval.source2
      $source3 = [string]$eval.source3
    } catch {
      $errorText = "$($_.Exception.Message) | stack: $($_.ScriptStackTrace)"
    }

    if ($top1) { $top1Count++ }
    if ($hit3) { $hit3Count++ }
    if ($answerHit) { $answerHitCount++ }
    if (-not [string]::IsNullOrWhiteSpace($errorText)) { $errorCount++ }

    $detailRows.Add([pscustomobject]@{
      group_name    = $g.name
      kb_id         = $g.kbId
      query_id      = $queryId
      query_text    = $queryText
      context_id    = $contextId
      title         = $title
      top1          = $top1
      hit3          = $hit3
      answer_hit    = $answerHit
      source1       = $source1
      source2       = $source2
      source3       = $source3
      assistant     = $assistantText
      tool_excerpt  = $toolText
      session_id    = $sessionId
      error         = $errorText
    })
  }

  $n = [Math]::Max(1, $rows.Count)
  $summaryRows.Add([pscustomobject]@{
    group_name = $g.name
    kb_id      = $g.kbId
    total      = $rows.Count
    top1_count = $top1Count
    top1_pct   = [Math]::Round(($top1Count * 100.0) / $n, 2)
    hit3_count = $hit3Count
    hit3_pct   = [Math]::Round(($hit3Count * 100.0) / $n, 2)
    answer_hit_count = $answerHitCount
    answer_hit_pct   = [Math]::Round(($answerHitCount * 100.0) / $n, 2)
    error_count = $errorCount
    timeout_sec = $TimeoutSec
  })
}

Export-CsvUtf8Bom -Rows ($detailRows.ToArray()) -Path $OutDetailCsv
Export-CsvUtf8Bom -Rows ($summaryRows.ToArray()) -Path $OutSummaryCsv

Write-Host ""
Write-Host "Done."
Write-Host "Detail:  $OutDetailCsv"
Write-Host "Summary: $OutSummaryCsv"

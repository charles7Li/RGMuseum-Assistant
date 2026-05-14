param(
  [string]$BaseUrl = "http://localhost:8080/api",
  [string]$AgentId = "",
  [string]$EvalCsv = "C:\Code\github\JChatMind\data\datasets\cmrc\cmrc2018_trial_eval.csv",
  [int]$Limit = 50,
  [int]$TimeoutSec = 45,
  [string]$OutCsv = "C:\Code\github\JChatMind\data\datasets\cmrc\cmrc_eval_result.csv"
)

[Console]::InputEncoding  = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if ([string]::IsNullOrWhiteSpace($AgentId)) {
  throw "Please pass -AgentId, for example: -AgentId 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'"
}
if (!(Test-Path $EvalCsv)) {
  throw "Eval CSV not found: $EvalCsv"
}

function Invoke-Api {
  param(
    [string]$Method,
    [string]$Url,
    [object]$Body = $null
  )
  $request = [System.Net.WebRequest]::Create($Url)
  $request.Method = $Method.ToUpperInvariant()
  $request.Accept = "application/json"

  if ($null -ne $Body) {
    $jsonBody = $Body | ConvertTo-Json -Depth 10
    $payload = [System.Text.Encoding]::UTF8.GetBytes($jsonBody)
    $request.ContentType = "application/json; charset=utf-8"
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
}

function Normalize-Text {
  param([string]$t)
  if ($null -eq $t) { return "" }
  $x = $t.ToLowerInvariant()
  $x = $x -replace "\s+", ""
  $x = $x -replace "[\p{P}\p{S}]", ""
  return $x
}

function Is-Hit {
  param(
    [string]$Pred,
    [string[]]$Golds
  )
  $p = Normalize-Text $Pred
  if ([string]::IsNullOrWhiteSpace($p)) { return $false }

  foreach ($g in $Golds) {
    $gn = Normalize-Text $g
    if ([string]::IsNullOrWhiteSpace($gn)) { continue }
    if ($p.Contains($gn) -or $gn.Contains($p)) {
      return $true
    }
  }
  return $false
}

$rows = Import-Csv -Path $EvalCsv -Encoding UTF8
if ($Limit -gt 0 -and $Limit -lt $rows.Count) {
  $rows = $rows | Select-Object -First $Limit
}

Write-Host "Eval count: $($rows.Count)"
Write-Host "AgentId: $AgentId"
Write-Host "BaseUrl: $BaseUrl"
if ($rows.Count -gt 0) {
  Write-Host "First query sample: $($rows[0].query_text)"
}

$results = @()
$hitCount = 0
$idx = 0

foreach ($r in $rows) {
  $idx++
  $queryId = $r.query_id
  $query = $r.query_text
  $goldAnswers = @()
  if ($r.gold_answers) {
    $goldAnswers = $r.gold_answers -split "\|\|"
  }

  Write-Host "[$idx/$($rows.Count)] $queryId"

  try {
    $sessionResp = Invoke-Api -Method "POST" -Url "$BaseUrl/chat-sessions" -Body @{
      agentId = $AgentId
      title   = "cmrc_eval_$queryId"
    }

    if ($null -eq $sessionResp -or $sessionResp.code -ne 200 -or -not $sessionResp.data -or [string]::IsNullOrWhiteSpace($sessionResp.data.chatSessionId)) {
      $raw = $sessionResp | ConvertTo-Json -Depth 10 -Compress
      throw "Create session failed: $raw"
    }
    $sessionId = $sessionResp.data.chatSessionId

    [void](Invoke-Api -Method "POST" -Url "$BaseUrl/chat-messages" -Body @{
      agentId   = $AgentId
      sessionId = $sessionId
      role      = "user"
      content   = $query
    })

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $assistantText = ""
    $timeoutReached = $true
    do {
      Start-Sleep -Milliseconds 800
      $msgResp = Invoke-Api -Method "GET" -Url "$BaseUrl/chat-messages/session/$sessionId"
      $msgs = @($msgResp.data.chatMessages)
      if ($msgs) {
        $assistants = @($msgs | Where-Object { $_.role -eq "assistant" -and -not [string]::IsNullOrWhiteSpace($_.content) })
        if ($assistants.Count -gt 0) {
          $assistantText = $assistants[-1].content
          $timeoutReached = $false
          break
        }
      }
    } while ((Get-Date) -lt $deadline)

    $hit = Is-Hit -Pred $assistantText -Golds $goldAnswers
    if ($hit) { $hitCount++ }

    $results += [pscustomobject]@{
      query_id     = $queryId
      query_text   = $query
      gold_answers = $r.gold_answers
      assistant    = $assistantText
      hit          = $hit
      session_id   = $sessionId
      error        = $(if ($timeoutReached) { "timeout_waiting_assistant" } else { "" })
    }
  }
  catch {
    $results += [pscustomobject]@{
      query_id     = $queryId
      query_text   = $query
      gold_answers = $r.gold_answers
      assistant    = ""
      hit          = $false
      session_id   = ""
      error        = $_.Exception.Message
    }
  }
}

$acc = 0.0
if ($rows.Count -gt 0) {
  $acc = [math]::Round(($hitCount * 100.0 / $rows.Count), 2)
}

$results | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8

Write-Host "--------------------------------------"
Write-Host "Answer Accuracy: $hitCount / $($rows.Count) = $acc%"
Write-Host "Result CSV: $OutCsv"
Write-Host "--------------------------------------"

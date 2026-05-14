param(
    [string]$BaseUrl = "http://localhost:8080/api",
    [string]$KbId = "cbc46d0a-1fd8-4a40-946a-7311518257c4",
    [string]$SourceDir = "C:\Code\github\JChatMind\data\datasets\cmrc",
    [switch]$DeleteExisting = $true
)


Add-Type -AssemblyName System.Net.Http
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Get-DocsInKb {
    param([string]$BaseUrl, [string]$KbId)
    
Add-Type -AssemblyName System.Net.Http
$resp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/documents/kb/$KbId"
    if ($resp.code -ne 200) { throw "查询文档失败: $($resp | ConvertTo-Json -Depth 6)" }
    return @($resp.data.documents)
}

function Remove-DocsInKb {
    param([string]$BaseUrl, [string]$KbId)

    
Add-Type -AssemblyName System.Net.Http
$docs = Get-DocsInKb -BaseUrl $BaseUrl -KbId $KbId
    Write-Host "准备删除文档数: $($docs.Count)"

    foreach ($d in $docs) {
        $docId = $d.id
        if (-not $docId) { continue }
        try {
            $del = Invoke-RestMethod -Method DELETE -Uri "$BaseUrl/documents/$docId"
            if ($del.code -eq 200) {
                Write-Host "已删除: $($d.filename) [$docId]"
            } else {
                Write-Warning "删除失败: $($d.filename) [$docId], respCode=$($del.code)"
            }
        } catch {
            Write-Warning "删除异常: $($d.filename) [$docId] => $($_.Exception.Message)"
        }
    }
}

function Upload-OneFile {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Url,
        [string]$KbId,
        [System.IO.FileInfo]$File
    )

    
Add-Type -AssemblyName System.Net.Http
$multi = New-Object System.Net.Http.MultipartFormDataContent
    $kbContent = New-Object System.Net.Http.StringContent($KbId, [System.Text.Encoding]::UTF8)
    $multi.Add($kbContent, "kbId")

    $fs = [System.IO.File]::OpenRead($File.FullName)
    try {
        $fileContent = New-Object System.Net.Http.StreamContent($fs)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/markdown")
        $multi.Add($fileContent, "file", $File.Name)

        $resp = $Client.PostAsync($Url, $multi).GetAwaiter().GetResult()
        $raw = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()

        if (-not $resp.IsSuccessStatusCode) {
            throw "HTTP $([int]$resp.StatusCode): $raw"
        }

        $json = $null
        try { $json = $raw | ConvertFrom-Json } catch {}
        if ($null -eq $json -or $json.code -ne 200) {
            throw "接口返回异常: $raw"
        }

        $docId = $json.data.documentId
        return $docId
    } finally {
        $fs.Dispose()
        $multi.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $SourceDir)) {
    throw "目录不存在: $SourceDir"
}

if ($DeleteExisting) {
    Remove-DocsInKb -BaseUrl $BaseUrl -KbId $KbId
}

$files = Get-ChildItem -Path $SourceDir -Recurse -File |
    Where-Object { $_.Extension.ToLower() -in @(".md", ".markdown") }

if ($files.Count -eq 0) {
    throw "未找到 md/markdown 文件: $SourceDir"
}

Write-Host "待上传文件数: $($files.Count)"

$handler = New-Object System.Net.Http.HttpClientHandler
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(120)

$uploadUrl = "$BaseUrl/documents/upload"
$ok = 0
$fail = 0

foreach ($f in $files) {
    try {
        $docId = Upload-OneFile -Client $client -Url $uploadUrl -KbId $KbId -File $f
        $ok++
        Write-Host "上传成功: $($f.Name) => $docId"
    } catch {
        $fail++
        Write-Warning "上传失败: $($f.FullName) => $($_.Exception.Message)"
    }
}

$client.Dispose()

$afterDocs = Get-DocsInKb -BaseUrl $BaseUrl -KbId $KbId
Write-Host ""
Write-Host "====== 完成 ======"
Write-Host "上传成功: $ok"
Write-Host "上传失败: $fail"
Write-Host "当前知识库文档数: $($afterDocs.Count)"
Write-Host "提示: 再去数据库执行 count(chunk_bge_m3 where kb_id=...) 确认 chunk 已写入。"


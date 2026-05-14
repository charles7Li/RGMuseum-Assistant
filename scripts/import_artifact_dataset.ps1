param(
    [string]$BaseUrl = "http://localhost:8080/api",
    [string]$KbId = "",
    [string]$KbName = "museum-ragas-kb",
    [string]$KbDescription = "KB for artifact_dataset RAGAS evaluation",
    [string]$DatasetRoot = "C:\Code\github\JChatMind\data\datasets\museum\data\artifact_dataset",
    [string]$EmbeddingRule = "title+content(500)",
    [switch]$DeleteExistingDocs = $false
)

Add-Type -AssemblyName System.Net.Http
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Invoke-JsonGet {
    param([string]$Url)
    return Invoke-RestMethod -Method GET -Uri $Url
}

function Invoke-JsonPost {
    param([string]$Url, [object]$Body)
    $json = $Body | ConvertTo-Json -Depth 10
    return Invoke-RestMethod -Method POST -Uri $Url -ContentType "application/json; charset=utf-8" -Body $json
}

function Resolve-KbId {
    param(
        [string]$BaseUrl,
        [string]$KbId,
        [string]$KbName,
        [string]$KbDescription,
        [string]$EmbeddingRule
    )
    if (-not [string]::IsNullOrWhiteSpace($KbId)) {
        return $KbId
    }

    $all = Invoke-JsonGet "$BaseUrl/knowledge-bases"
    if ($all.code -ne 200) {
        throw "Failed to list knowledge bases: $($all | ConvertTo-Json -Depth 6)"
    }
    $kbs = @($all.data.knowledgeBases)
    $existing = $kbs | Where-Object { $_.name -eq $KbName } | Select-Object -First 1
    if ($null -ne $existing -and $existing.id) {
        return [string]$existing.id
    }

    $create = Invoke-JsonPost "$BaseUrl/knowledge-bases" @{
        name = $KbName
        description = $KbDescription
        embeddingRule = $EmbeddingRule
    }
    if ($create.code -ne 200) {
        throw "Failed to create knowledge base: $($create | ConvertTo-Json -Depth 6)"
    }
    $newKbId = [string]$create.data.knowledgeBaseId
    if ([string]::IsNullOrWhiteSpace($newKbId)) {
        throw "Create KB returned empty knowledgeBaseId."
    }
    return $newKbId
}

function Get-DocsInKb {
    param([string]$BaseUrl, [string]$KbId)
    $resp = Invoke-JsonGet "$BaseUrl/documents/kb/$KbId"
    if ($resp.code -ne 200) {
        throw "Failed to query docs in KB: $($resp | ConvertTo-Json -Depth 6)"
    }
    return @($resp.data.documents)
}

function Remove-DocsInKb {
    param([string]$BaseUrl, [string]$KbId)
    $docs = Get-DocsInKb -BaseUrl $BaseUrl -KbId $KbId
    Write-Host "Deleting existing docs: $($docs.Count)"
    foreach ($d in $docs) {
        if (-not $d.id) { continue }
        try {
            $del = Invoke-RestMethod -Method DELETE -Uri "$BaseUrl/documents/$($d.id)"
            if ($del.code -eq 200) {
                Write-Host "Deleted doc: $($d.filename)"
            } else {
                Write-Warning "Delete doc failed: $($d.filename)"
            }
        } catch {
            Write-Warning "Delete doc exception: $($d.filename) => $($_.Exception.Message)"
        }
    }
}

function Upload-MultipartFile {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Url,
        [hashtable]$Fields,
        [string]$FieldFileName,
        [System.IO.FileInfo]$File,
        [string]$ContentType
    )

    $multi = New-Object System.Net.Http.MultipartFormDataContent
    foreach ($k in $Fields.Keys) {
        $v = [string]$Fields[$k]
        $multi.Add((New-Object System.Net.Http.StringContent($v, [System.Text.Encoding]::UTF8)), $k)
    }

    $fs = [System.IO.File]::OpenRead($File.FullName)
    try {
        $fc = New-Object System.Net.Http.StreamContent($fs)
        if (-not [string]::IsNullOrWhiteSpace($ContentType)) {
            $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($ContentType)
        }
        $multi.Add($fc, $FieldFileName, $File.Name)

        $resp = $Client.PostAsync($Url, $multi).GetAwaiter().GetResult()
        $raw = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $resp.IsSuccessStatusCode) {
            throw "HTTP $([int]$resp.StatusCode): $raw"
        }
        $json = $null
        try { $json = $raw | ConvertFrom-Json } catch {}
        if ($null -eq $json -or $json.code -ne 200) {
            throw "API returned error: $raw"
        }
        return $json
    } finally {
        $fs.Dispose()
        $multi.Dispose()
    }
}

function Build-MarkdownFromInfo {
    param([object]$Info)
    $lines = New-Object System.Collections.Generic.List[string]
    $name = [string]$Info.name
    if (-not [string]::IsNullOrWhiteSpace($name)) {
        $lines.Add("# $name") | Out-Null
        $lines.Add("") | Out-Null
    }

    foreach ($key in @("id","era","category","museum","origin","summary")) {
        if ($Info.PSObject.Properties.Name -contains $key) {
            $val = [string]$Info.$key
            if (-not [string]::IsNullOrWhiteSpace($val)) {
                $lines.Add("**$key**: $val") | Out-Null
            }
        }
    }
    $lines.Add("") | Out-Null

    if ($Info.PSObject.Properties.Name -contains "description" -and $Info.description) {
        $lines.Add("## description") | Out-Null
        $lines.Add([string]$Info.description) | Out-Null
        $lines.Add("") | Out-Null
    }

    if ($Info.PSObject.Properties.Name -contains "features" -and $Info.features) {
        $lines.Add("## features") | Out-Null
        foreach ($x in @($Info.features)) {
            $lines.Add("- $([string]$x)") | Out-Null
        }
        $lines.Add("") | Out-Null
    }

    if ($Info.PSObject.Properties.Name -contains "value_points" -and $Info.value_points) {
        $lines.Add("## value_points") | Out-Null
        foreach ($x in @($Info.value_points)) {
            $lines.Add("- $([string]$x)") | Out-Null
        }
        $lines.Add("") | Out-Null
    }

    if ($Info.PSObject.Properties.Name -contains "qa_pairs" -and $Info.qa_pairs) {
        $lines.Add("## qa_pairs") | Out-Null
        foreach ($qa in @($Info.qa_pairs)) {
            $q = [string]$qa.q
            $a = [string]$qa.a
            if ($q) { $lines.Add("- Q: $q") | Out-Null }
            if ($a) { $lines.Add("  A: $a") | Out-Null }
        }
        $lines.Add("") | Out-Null
    }

    return ($lines -join [Environment]::NewLine)
}

if (-not (Test-Path -LiteralPath $DatasetRoot)) {
    throw "Dataset root not found: $DatasetRoot"
}

$resolvedKbId = Resolve-KbId -BaseUrl $BaseUrl -KbId $KbId -KbName $KbName -KbDescription $KbDescription -EmbeddingRule $EmbeddingRule
Write-Host "KB_ID: $resolvedKbId"

if ($DeleteExistingDocs) {
    Remove-DocsInKb -BaseUrl $BaseUrl -KbId $resolvedKbId
}

$tmpMdRoot = Join-Path $DatasetRoot "_tmp_md_upload"
if (Test-Path -LiteralPath $tmpMdRoot) {
    Remove-Item -LiteralPath $tmpMdRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $tmpMdRoot -Force | Out-Null

$artifactDirs = @(Get-ChildItem -LiteralPath $DatasetRoot -Directory | Where-Object { $_.Name -like "artifact_*" })
if ($artifactDirs.Count -eq 0) {
    throw "No artifact_* folders under: $DatasetRoot"
}

$handler = New-Object System.Net.Http.HttpClientHandler
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(180)

$docUploadUrl = "$BaseUrl/documents/upload"
$imgUploadUrl = "$BaseUrl/rag/images/upload"

$docOk = 0
$docFail = 0
$imgOk = 0
$imgFail = 0

foreach ($dir in $artifactDirs) {
    $infoPath = Join-Path $dir.FullName "info.json"
    if (-not (Test-Path -LiteralPath $infoPath)) {
        Write-Warning "skip (no info.json): $($dir.Name)"
        continue
    }
    $info = Get-Content -Raw -Encoding UTF8 $infoPath | ConvertFrom-Json
    $artifactId = [string]$info.id
    if ([string]::IsNullOrWhiteSpace($artifactId)) {
        $artifactId = $dir.Name
    }

    # 1) Upload markdown document
    try {
        $md = Build-MarkdownFromInfo -Info $info
        $mdPath = Join-Path $tmpMdRoot "$artifactId.md"
        [System.IO.File]::WriteAllText($mdPath, $md, [System.Text.UTF8Encoding]::new($false))
        $mdFile = Get-Item -LiteralPath $mdPath

        $null = Upload-MultipartFile -Client $client -Url $docUploadUrl -Fields @{
            kbId = $resolvedKbId
            embeddingRule = $EmbeddingRule
        } -FieldFileName "file" -File $mdFile -ContentType "text/markdown"
        $docOk++
    } catch {
        $docFail++
        Write-Warning "doc upload failed: $artifactId => $($_.Exception.Message)"
    }

    # 2) Upload image
    try {
        $imgName = [string]$info.image
        $imgFile = $null
        if (-not [string]::IsNullOrWhiteSpace($imgName)) {
            $candidate = Join-Path $dir.FullName $imgName
            if (Test-Path -LiteralPath $candidate) {
                $imgFile = Get-Item -LiteralPath $candidate
            }
        }
        if ($null -eq $imgFile) {
            $imgFile = Get-ChildItem -LiteralPath $dir.FullName -File |
                Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp|bmp)$' } |
                Select-Object -First 1
        }
        if ($null -eq $imgFile) {
            throw "no image file found in $($dir.Name)"
        }

        $ctype = "image/jpeg"
        switch ($imgFile.Extension.ToLower()) {
            ".png" { $ctype = "image/png" }
            ".webp" { $ctype = "image/webp" }
            ".bmp" { $ctype = "image/bmp" }
            ".jpeg" { $ctype = "image/jpeg" }
            ".jpg" { $ctype = "image/jpeg" }
        }

        $null = Upload-MultipartFile -Client $client -Url $imgUploadUrl -Fields @{
            kbId = $resolvedKbId
        } -FieldFileName "file" -File $imgFile -ContentType $ctype
        $imgOk++
    } catch {
        $imgFail++
        Write-Warning "image upload failed: $artifactId => $($_.Exception.Message)"
    }
}

$client.Dispose()

Write-Host ""
Write-Host "====== import finished ======"
Write-Host "kb_id: $resolvedKbId"
Write-Host "artifact_folders: $($artifactDirs.Count)"
Write-Host "doc_upload_ok: $docOk"
Write-Host "doc_upload_fail: $docFail"
Write-Host "image_upload_ok: $imgOk"
Write-Host "image_upload_fail: $imgFail"
Write-Host ""
Write-Host "Use this KB id for ragas_eval.py --kb-id $resolvedKbId"


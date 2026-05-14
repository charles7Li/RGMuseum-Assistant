param(
  [string]$SourceRoot = "C:\Code\github\JChatMind\data\datasets\museum\data\picture",
  [string]$OutputRoot = "C:\Code\github\JChatMind\data\datasets\museum\data\artifact_dataset",
  [switch]$CleanOutput,
  [int]$QaPairCount = 4,
  [switch]$RandomizeQa = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-JsonItems {
  param([string]$Path)
  $raw = Get-Content -Raw -Encoding UTF8 $Path
  if ([string]::IsNullOrWhiteSpace($raw)) { return @() }
  $trim = $raw.Trim()
  if ($trim.StartsWith("[")) {
    return @($trim | ConvertFrom-Json)
  }
  $lines = Get-Content -Encoding UTF8 $Path | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
  $items = @()
  foreach ($line in $lines) {
    $items += ($line | ConvertFrom-Json)
  }
  return $items
}

function Normalize-Text {
  param([string]$Text)
  if ([string]::IsNullOrWhiteSpace($Text)) { return "" }
  $x = $Text.ToLowerInvariant()
  $x = $x -replace "\.jpg|\.jpeg|\.png|\.webp|\.bmp", ""
  $x = $x -replace "[\s\p{P}\p{S}]", ""
  return $x
}

function Get-OverlapScore {
  param(
    [string]$A,
    [string]$B
  )
  if ([string]::IsNullOrWhiteSpace($A) -or [string]::IsNullOrWhiteSpace($B)) { return 0.0 }
  $setA = New-Object System.Collections.Generic.HashSet[string]
  $setB = New-Object System.Collections.Generic.HashSet[string]
  foreach ($ch in $A.ToCharArray()) { [void]$setA.Add([string]$ch) }
  foreach ($ch in $B.ToCharArray()) { [void]$setB.Add([string]$ch) }
  $common = 0
  foreach ($ch in $setA) {
    if ($setB.Contains($ch)) { $common++ }
  }
  $den = [Math]::Max(1, [Math]::Min($setA.Count, $setB.Count))
  return ($common * 1.0) / $den
}

function Find-ImageForItem {
  param(
    [object]$Item,
    [string[]]$MuseumDirs,
    [object[]]$GlobalCandidates
  )
  $allCandidates = @()
  foreach ($d in @($MuseumDirs)) {
    if (-not [string]::IsNullOrWhiteSpace($d) -and (Test-Path -LiteralPath $d)) {
      $allCandidates += @(Get-ChildItem -LiteralPath $d -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp|bmp)$' })
    }
  }
  if ($GlobalCandidates.Count -gt 0) {
    $allCandidates += $GlobalCandidates
  }

  if ($allCandidates.Count -eq 0) { return $null }

  if ($Item.PSObject.Properties.Name -contains "image") {
    $imageName = [string]$Item.image
    if (-not [string]::IsNullOrWhiteSpace($imageName)) {
      $exact = $allCandidates | Where-Object { $_.Name -ieq $imageName } | Select-Object -First 1
      if ($null -ne $exact) { return $exact }
    }
  }

  $targetName = Normalize-Text ([string]$Item.name)
  $fuzzy = $allCandidates | Where-Object {
    $n = Normalize-Text $_.BaseName
    $n.Contains($targetName) -or $targetName.Contains($n)
  } | Select-Object -First 1
  if ($null -ne $fuzzy) { return $fuzzy }

  # 兜底：按名称字符重合度选最相近图片
  $best = $null
  $bestScore = 0.0
  foreach ($cand in $allCandidates) {
    $candN = Normalize-Text $cand.BaseName
    $score = Get-OverlapScore -A $targetName -B $candN
    if ($score -gt $bestScore) {
      $bestScore = $score
      $best = $cand
    }
  }
  if ($null -ne $best -and $bestScore -ge 0.6) {
    return $best
  }

  return $null
}

function Write-JsonUtf8 {
  param(
    [object]$Obj,
    [string]$Path
  )
  $json = $Obj | ConvertTo-Json -Depth 20
  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Safe-String {
  param([object]$Value, [string]$Default = "")
  if ($null -eq $Value) { return $Default }
  $s = [string]$Value
  if ([string]::IsNullOrWhiteSpace($s)) { return $Default }
  return $s.Trim()
}

function Get-OptionalString {
  param(
    [object]$Item,
    [string]$Name,
    [string]$Default = ""
  )
  if ($null -eq $Item) { return $Default }
  if ($Item.PSObject.Properties.Name -contains $Name) {
    return Safe-String $Item.$Name $Default
  }
  return $Default
}

function Set-OrAddProperty {
  param(
    [object]$Item,
    [string]$Name,
    [object]$Value
  )
  if ($Item.PSObject.Properties.Name -contains $Name) {
    $Item.$Name = $Value
  } else {
    $Item | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
  }
}

function Build-ValueText {
  param(
    [string[]]$ValuePoints,
    [string]$Description
  )

  $cleanPoints = @(
    $ValuePoints |
      ForEach-Object { Safe-String $_ "" } |
      Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
  )

  if ($cleanPoints.Count -gt 0) {
    $joined = $cleanPoints -join "、"
    $singlePoint = $cleanPoints.Count -eq 1
    $point = $cleanPoints[0]
    $looksSuspicious = $singlePoint -and (
      $point.Length -le 4 -or
      $point -match "钟$" -or
      $point -match "^[A-Za-z0-9]+$"
    )
    if (-not $looksSuspicious) {
      return $joined
    }
  }

  if (-not [string]::IsNullOrWhiteSpace($Description)) {
    if ($Description -match "推算.*时间|古地球|地层|年代") {
      return "具有古生物研究价值，可用于推算古地球时间"
    }
    if ($Description -match "外销|贸易") {
      return "具有外销贸易和文化交流研究价值"
    }
    if ($Description -match "宫廷|礼制") {
      return "具有宫廷制度与工艺研究价值"
    }
    if ($Description -match "工艺|造型|审美") {
      return "具有工艺、造型和审美研究价值"
    }
  }

  return "具有一定历史与艺术研究价值"
}

function Pick-One {
  param(
    [object[]]$Items,
    [bool]$Randomize = $true
  )
  if ($null -eq $Items -or $Items.Count -eq 0) { return "" }
  if (-not $Randomize) { return [string]$Items[0] }
  return [string]($Items | Get-Random)
}

function Pick-Weighted {
  param(
    [object[]]$Items,
    [int[]]$Weights
  )
  if ($null -eq $Items -or $Items.Count -eq 0) { return "" }
  if ($null -eq $Weights -or $Weights.Count -ne $Items.Count) {
    return [string]($Items | Get-Random)
  }
  $total = 0
  foreach ($w in $Weights) {
    $total += [Math]::Max(0, [int]$w)
  }
  if ($total -le 0) {
    return [string]($Items | Get-Random)
  }
  $roll = Get-Random -Minimum 1 -Maximum ($total + 1)
  $acc = 0
  for ($i = 0; $i -lt $Items.Count; $i++) {
    $acc += [Math]::Max(0, [int]$Weights[$i])
    if ($roll -le $acc) {
      return [string]$Items[$i]
    }
  }
  return [string]($Items[$Items.Count - 1])
}

function Join-Readable {
  param([string[]]$Items, [string]$Separator = "、")
  if ($null -eq $Items) { return "" }
  $clean = @($Items | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ($clean.Count -eq 0) { return "" }
  return ($clean -join $Separator)
}

function Build-QAPairs {
  param(
    [object]$Item,
    [int]$Count = 4,
    [bool]$Randomize = $true
  )

  $name = Safe-String $Item.name "该文物"
  $era = Safe-String $Item.era "未知时代"
  $category = Safe-String $Item.category "文物"
  $museum = Safe-String $Item.museum "博物馆"
  $origin = Safe-String $Item.origin "未知产地"
  $summary = Get-OptionalString $Item "summary" ""
  $desc = Get-OptionalString $Item "description" ""

  $features = @()
  if ($Item.PSObject.Properties.Name -contains "features" -and $Item.features) {
    $features = @($Item.features | ForEach-Object { Safe-String $_ "" } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  }
  $valuePoints = @()
  if ($Item.PSObject.Properties.Name -contains "value_points" -and $Item.value_points) {
    $valuePoints = @($Item.value_points | ForEach-Object { Safe-String $_ "" } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  }

  $featureText = if ($features.Count -gt 0) { ($features -join "、") } else { "工艺与造型特征较为鲜明" }
  $valueText = Build-ValueText -ValuePoints $valuePoints -Description $desc
  $primaryFeature = if ($features.Count -gt 0) { $features[0] } elseif ($valuePoints.Count -gt 0) { $valuePoints[0] } else { "工艺与造型特征" }
  $brief = if (-not [string]::IsNullOrWhiteSpace($summary)) { $summary } else { "${era}${category}代表器物：$name" }
  $detail = if (-not [string]::IsNullOrWhiteSpace($desc)) { $desc } else { "$name体现了${era}时期${category}在工艺、造型和文化寓意方面的综合特征。" }

  $questionBank = @(
    @{
      key = "identify"
      questions = @(
        "这是什么？",
        "请直接告诉我它是什么文物。",
        "能先识别一下这件文物吗？"
      )
      weight = 5
    },
    @{
      key = "overview"
      questions = @(
        "请介绍一下这件文物。",
        "能简单讲讲它吗？",
        "用一两句话概括这件文物。",
        "这件文物的大致信息是什么？"
      )
      weight = 4
    },
    @{
      key = "identity_meta"
      questions = @(
        "这件文物属于什么时代和类别？",
        "它大概是什么年代、什么类型？",
        "能说明它的时代和文物类别吗？",
        "从基本属性上看，它属于哪个时期、哪一类？"
      )
      weight = 3
    },
    @{
      key = "feature"
      questions = @(
        "它有什么特点？",
        "它的工艺和造型有什么特征？",
        "这件文物最突出的地方是什么？",
        "它的主要特征和工艺亮点有哪些？",
        "从外观和工艺上看，它有什么看点？"
      )
      weight = 6
    },
    @{
      key = "value"
      questions = @(
        "它有什么历史或艺术价值？",
        "这件文物的价值主要体现在哪里？",
        "它为什么值得关注？",
        "能说说它的文化、艺术或研究价值吗？"
      )
      weight = 4
    },
    @{
      key = "origin"
      questions = @(
        "这件文物的产地和收藏地是哪里？",
        "它来自哪里、现藏哪里？",
        "这件文物的来源和收藏单位是什么？",
        "能告诉我它的产地和博物馆吗？"
      )
      weight = 2
    },
    @{
      key = "compare_short"
      questions = @(
        "如果只看核心特征，应该怎么理解这件文物？",
        "换个说法，它最核心的信息是什么？",
        "你可以把它概括得更口语一点吗？",
        "如果我只想记住三个点，应该记什么？"
      )
      weight = 2
    }
  )

  $answerPool = @{
    "identify" = @(
      "这是${name}，属于${era}的${category}。",
      "${name}是一件${era}${category}文物。",
      "${name}属于${era}，类别是${category}。"
    )
    "overview" = @(
      "${name}是一件${era}${category}文物，体现了${featureText}。",
      "${name}是${era}${category}代表器物，现藏于${museum}。",
      "${name}兼具时代特征和工艺特色。"
    )
    "identity_meta" = @(
      "${name}属于${era}，类别是${category}。",
      "${name}的时代是${era}，类别是${category}。",
      "${name}可归为${era}${category}。"
    )
    "feature" = @(
      "${name}的特点是${featureText}。",
      "${name}主要看工艺和造型，体现在${featureText}。",
      "从已知信息看，${name}体现出${featureText}。"
    )
    "value" = @(
      "${name}的价值主要是${valueText}。",
      "${name}值得关注，因为它具有${valueText}。",
      "综合来看，${name}具备${valueText}。"
    )
    "origin" = @(
      "${name}产自${origin}，现藏于${museum}。",
      "${name}的产地是${origin}，收藏单位是${museum}。",
      "${name}来自${origin}，目前由${museum}收藏。"
    )
    "compare_short" = @(
      "${name}可概括为：${era}、${category}、${primaryFeature}。",
      "只记三个点就行：${era}、${category}、${primaryFeature}。",
      "${name}就是一件带有${primaryFeature}的${era}${category}。"
    )
  }

  $targetCount = [Math]::Max(2, [Math]::Min(12, $Count))
  $pairs = New-Object System.Collections.Generic.List[object]
  $usedQuestions = New-Object System.Collections.Generic.HashSet[string]

  $queue = New-Object System.Collections.Generic.List[string]
  if ($Randomize) {
    $sortedBanks = $questionBank | Sort-Object { Get-Random }
  } else {
    $sortedBanks = $questionBank
  }
  foreach ($bank in $sortedBanks) {
    $questions = @($bank.questions)
    if ($Randomize) {
      $questions = $questions | Sort-Object { Get-Random }
    }
    foreach ($question in $questions) {
      $queue.Add([string]$question)
    }
  }

  while ($pairs.Count -lt $targetCount) {
    $q = if ($queue.Count -gt 0) { [string]$queue[0] } else { "" }
    if ($queue.Count -gt 0) {
      $queue.RemoveAt(0)
    }
    if ([string]::IsNullOrWhiteSpace($q)) { break }

    if ($usedQuestions.Contains($q)) {
      continue
    } else {
      [void]$usedQuestions.Add($q)
    }

    $bucketKey = $null
    foreach ($bank in $questionBank) {
      if ($bank.questions -contains $q) {
        $bucketKey = [string]$bank.key
        break
      }
    }
    if ([string]::IsNullOrWhiteSpace($bucketKey)) { continue }

    $answers = @($answerPool[$bucketKey])
    $a = Pick-One -Items $answers -Randomize $Randomize
    if ([string]::IsNullOrWhiteSpace($a)) { continue }

    $pairs.Add([pscustomobject]@{
      q = $q
      a = $a
      keywords = @($name, $era, $category) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
    }) | Out-Null

    if (-not $Randomize -and $pairs.Count -ge $questionBank.Count) { break }
  }

  if ($pairs.Count -lt 2) {
    $pairs.Clear()
    $pairs.Add([pscustomobject]@{
      q = "这是什么？"
      a = "这是${name}，属于${era}的${category}类文物。"
      keywords = @($name, $era, $category) | Select-Object -Unique
    }) | Out-Null
    $pairs.Add([pscustomobject]@{
      q = "它有什么特点？"
      a = "${name}的主要特点包括：${featureText}。"
      keywords = @($name, "特点", "工艺") | Select-Object -Unique
    }) | Out-Null
  }

  return ,$pairs.ToArray()
}

if ($CleanOutput -and (Test-Path -LiteralPath $OutputRoot)) {
  Remove-Item -LiteralPath $OutputRoot -Recurse -Force
}

if (-not (Test-Path -LiteralPath $OutputRoot)) {
  New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
}

$summaryPaths = @(
  "$SourceRoot\广州博物馆\汇总.json",
  "$SourceRoot\故宫博物馆\汇总.json",
  "$SourceRoot\故宫博物馆2\汇总.json"
)

$allItems = @()
foreach ($p in $summaryPaths) {
  if (Test-Path -LiteralPath $p) {
    $allItems += Read-JsonItems -Path $p
  }
}

# 用户补充条目 artifact_036
$extra036 = [pscustomobject]@{
  id = "artifact_036"
  name = "紫檀木雕花纹椅"
  era = "清代"
  category = "家具"
  museum = "故宫博物院"
  origin = "中国"
  description = "紫檀木雕花纹椅是清代宫廷家具中的典型代表，以珍贵材质与精湛雕刻工艺著称。紫檀木质地坚硬细密，色泽深沉稳重，是明清时期最为珍贵的家具用材之一，多用于宫廷及高等级器物制作。该椅以紫檀为材，整体结构严谨，比例匀称，体现出传统家具制作中的榫卯工艺特点。椅背及扶手部位常雕刻花卉、卷草或吉祥纹样，刀法细腻，层次分明，兼具装饰性与艺术性。此类家具不仅用于日常起居，也具有身份象征意义，反映出使用者的地位与审美品味。紫檀木雕椅在材质选择、结构设计与装饰工艺上均达到较高水平，是研究清代宫廷家具、传统木作工艺及等级文化的重要实物。"
  features = @("紫檀木材质坚硬细密", "榫卯结构连接", "雕刻花纹装饰", "造型比例严谨")
  value_points = @("体现清代高等级家具用材标准", "反映传统木作工艺与结构技术", "具有身份象征与审美价值")
  image = "artifact_036.jpg"
  image_caption = "雕刻花纹的紫檀木椅"
  keywords = @("紫檀", "清代家具", "雕刻", "宫廷家具")
}
$allItems += $extra036

# 去重：保留最后一条（确保 artifact_036 以补充版为准）
$dedup = @{}
foreach ($it in $allItems) {
  $dedup[[string]$it.id] = $it
}
$items = @($dedup.Values | Sort-Object { [string]$_.id })
$globalImages = @()
if (Test-Path -LiteralPath $SourceRoot) {
  $globalImages = @(Get-ChildItem -LiteralPath $SourceRoot -Recurse -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp|bmp)$' })
}

$ok = 0
$missing = New-Object System.Collections.Generic.List[string]

foreach ($item in $items) {
  $id = [string]$item.id
  if ([string]::IsNullOrWhiteSpace($id)) { continue }

  $caseDir = Join-Path $OutputRoot $id
  New-Item -ItemType Directory -Path $caseDir -Force | Out-Null

  $museumName = [string]$item.museum
  $museumDirs = @()
  $directDir = Join-Path $SourceRoot $museumName
  if (Test-Path -LiteralPath $directDir) {
    $museumDirs += $directDir
  }
  # museum 字段里常写“故宫博物院”，目录是“故宫博物馆/故宫博物馆2”
  if ($museumName -eq "故宫博物院") {
    $museumDirs += @(Join-Path $SourceRoot "故宫博物馆")
    $museumDirs += @(Join-Path $SourceRoot "故宫博物馆2")
  }

  $img = Find-ImageForItem -Item $item -MuseumDirs $museumDirs -GlobalCandidates $globalImages
  if ($null -eq $img -and [string]$item.id -eq "artifact_036") {
    $img036 = Join-Path $SourceRoot "紫檀木雕花纹椅.jpg"
    if (Test-Path -LiteralPath $img036) {
      $img = Get-Item -LiteralPath $img036
    }
  }

  if ($null -eq $img) {
    $missing.Add("$id | $([string]$item.name)") | Out-Null
    continue
  }

  $targetImageName = [string]$item.image
  if ([string]::IsNullOrWhiteSpace($targetImageName)) {
    $targetImageName = $img.Name
  }

  Copy-Item -LiteralPath $img.FullName -Destination (Join-Path $caseDir $targetImageName) -Force
  Set-OrAddProperty -Item $item -Name "image" -Value $targetImageName
  Set-OrAddProperty -Item $item -Name "qa_pairs" -Value (Build-QAPairs -Item $item -Count $QaPairCount -Randomize $RandomizeQa)

  Write-JsonUtf8 -Obj $item -Path (Join-Path $caseDir "info.json")
  $ok++
}

Write-Host "Done."
Write-Host "OutputRoot: $OutputRoot"
Write-Host "TotalItems: $($items.Count)"
Write-Host "Built: $ok"
Write-Host "MissingImage: $($missing.Count)"
if ($missing.Count -gt 0) {
  $missingPath = Join-Path $OutputRoot "_missing_images.txt"
  $missing | Set-Content -LiteralPath $missingPath -Encoding UTF8
  Write-Host "Missing list: $missingPath"
}

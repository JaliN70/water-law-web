# Parse plumbing spec PDF text -> js/plumbing-spec.js
$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$textFile = Join-Path $PSScriptRoot 'plumbing-spec-text.txt'
$outFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'js\plumbing-spec.js'

function Clean([string]$s) {
  if (-not $s) { return '' }
  ($s -replace '\s+', ' ').Trim()
}

function ShortLabel([string]$text) {
  $text = Clean $text
  if ($text.Length -le 28) { return $text }
  return $text.Substring(0, 28) + [char]0x2026
}

function EscapeJs([string]$s) {
  if (-not $s) { return '' }
  $s = $s -replace '\\', '\\\\'
  $s = $s -replace "'", "\'"
  $s = $s -replace "`r", ''
  $s = $s -replace "`n", '\n'
  return $s
}

$lines = [System.IO.File]::ReadAllLines($textFile, [System.Text.Encoding]::UTF8)
$startIdx = 0
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -match '^第一章\s+總則' -and $i -gt 20) { $startIdx = $i; break }
}

$chapters = New-Object System.Collections.Generic.List[object]
$currentChapter = $null
$currentArt = $null
$bodyLines = New-Object System.Collections.Generic.List[string]

function FlushArticle {
  if (-not $currentChapter -or -not $currentArt) { return }
  $body = Clean ($bodyLines -join ' ')
  if (-not $body) { $body = $currentArt.title }
  $labelSrc = if ($currentArt.title) { $currentArt.title + ' ' + $body } else { $body }
  $currentChapter.articles.Add([ordered]@{
    art = $currentArt.id
    label = (ShortLabel $labelSrc)
    body = $body
  }) | Out-Null
  $bodyLines.Clear()
}

function FlushChapter {
  FlushArticle
  if ($currentChapter -and $currentChapter.articles.Count -gt 0) {
    $chapters.Add($currentChapter) | Out-Null
  }
}

function IsPageMarker([string]$line) {
  return ($line -match '^\d+\s*-\s*\d+$') -or ($line -match '^附錄-\s*\d+$')
}

function ChapterIcon([string]$title) {
  switch -Regex ($title) {
    '總則' { return [char]0x1F4CB }
    '配管' { return [char]0x1F4D0 }
    '給水' { return [char]0x1F4A7 }
    '排水' { return [char]0x1F327 + [char]0xFE0F }
    default { return [char]0x1F4CA }
  }
}

for ($i = $startIdx; $i -lt $lines.Count; $i++) {
  $line = $lines[$i].Trim()
  if (-not $line -or (IsPageMarker $line)) { continue }

  if ($line -match '^第[一二三四]章\s*(.+)$') {
    FlushChapter
    $title = Clean $matches[1]
    $num = switch -Regex ($line) {
      '第一章' { 1 }; '第二章' { 2 }; '第三章' { 3 }; '第四章' { 4 }; default { 0 }
    }
    $currentChapter = [ordered]@{
      id = "PLUMB-ch$num"
      icon = ChapterIcon $title
      title = $title
      subtitle = ($line -replace '\s+', ' ').Trim()
      desc = ($line -replace '\s+', ' ').Trim()
      articles = New-Object System.Collections.Generic.List[object]
    }
    $currentArt = $null
    $bodyLines.Clear()
    continue
  }

  if ($line -match '^附錄\s+(.+)$' -and $line -notmatch '^附錄之') {
    FlushChapter
    $title = Clean $matches[1]
    $currentChapter = [ordered]@{
      id = 'PLUMB-appendix'
      icon = [char]0x1F4CA
      title = '附錄'
      subtitle = $line
      desc = $line
      articles = New-Object System.Collections.Generic.List[object]
    }
    $currentArt = $null
    $bodyLines.Clear()
    continue
  }

  if ($line -match '^(\d+\.\d+(?:\.\d+)?)\s+(.+)$') {
    FlushArticle
    $currentArt = [ordered]@{ id = $matches[1]; title = Clean $matches[2] }
    $bodyLines.Clear()
    if ($matches[2].Length -gt 15) { [void]$bodyLines.Add($matches[2]) }
    continue
  }

  if ($currentChapter -and $currentChapter.id -eq 'PLUMB-appendix' -and $line -match '^(\d+)\s+(.+)$' -and $line -notmatch '^\d+\.\d') {
    FlushArticle
    $currentArt = [ordered]@{ id = "A-$($matches[1])"; title = Clean $matches[2] }
    $bodyLines.Clear()
    continue
  }

  if ($currentArt) {
    [void]$bodyLines.Add($line)
  }
}
FlushChapter

$categoriesJs = New-Object System.Collections.Generic.List[string]
foreach ($ch in $chapters) {
  $artLines = New-Object System.Collections.Generic.List[string]
  foreach ($a in $ch.articles) {
    [void]$artLines.Add("      { art: '$($a.art)', label: '$(EscapeJs $a.label)', body: '$(EscapeJs $a.body)' }")
  }
  $artsStr = $artLines -join ",`n"
  [void]$categoriesJs.Add(@"
  {
    id: '$($ch.id)',
    icon: '$($ch.icon)',
    title: '$(EscapeJs $ch.title)',
    subtitle: '$(EscapeJs $ch.subtitle)',
    desc: '$(EscapeJs $ch.desc)',
    articles: [
$artsStr
    ],
  }
"@)
}

$js = @"
/** Plumbing spec parsed from PDF */
const PLUMBING_SPEC_LAW = {
  id: 'PLUMBING-SPEC',
  pcode: null,
  name: '建築物給水排水設備設計技術規範',
  shortName: '設計技術規範',
  icon: '📘',
  amended: '民國 111 年 12 月 29 日',
  fullUrl: null,
  source: 'pdf',
  pdfFile: 'assets/plumbing-spec.pdf',
  categories: [
$($categoriesJs -join ",`n")
  ],
};
"@

[System.IO.File]::WriteAllText($outFile, $js, $utf8NoBom)
$total = ($chapters | ForEach-Object { $_.articles.Count } | Measure-Object -Sum).Sum
Write-Host "Chapters: $($chapters.Count), Articles: $total"
Write-Host "Output: $outFile ($((Get-Item $outFile).Length) bytes)"

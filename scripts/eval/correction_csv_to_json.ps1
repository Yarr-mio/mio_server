<#
  정정 대응 시험 세트 CSV -> JSON 변환 (이슈 #554, 상위 #551).
  형식과 작성 규칙은 src/test/resources/eval/correction/AUTHORING.md 를 따른다.

  홀수 열(t1, t3, ...)은 사용자, 짝수 열(t2, t4, ...)은 AI 발화로 읽고, 비어 있는 칸에서 끝낸다.
  correction 값은 type 에서 정해진다(control 이 아니면 정정). 내용 검증은 JSON 을 읽는
  CorrectionEvalSet 로더가 한다 — 이 스크립트는 형식 변환만 한다.
#>
param(
    [Parameter(Mandatory = $true)][string]$Csv,
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = 'Stop'

$rows = Import-Csv -Path $Csv -Encoding UTF8
$cases = @()
foreach ($row in $rows) {
    $id = "$($row.id)".Trim()
    if (-not $id) { continue }
    if ($id.StartsWith('TEMPLATE-')) {
        throw "템플릿 예시 행이 남아 있다: $id — 지우고 다시 실행한다"
    }

    $type = "$($row.type)".Trim()
    $turns = @()
    for ($i = 1; $i -le 9; $i++) {
        $prop = $row.PSObject.Properties["t$i"]
        if ($null -eq $prop) { break }
        $text = "$($prop.Value)"
        if ([string]::IsNullOrWhiteSpace($text)) { break }
        $role = if ($i % 2 -eq 1) { 'USER' } else { 'ASSISTANT' }
        $turns += [ordered]@{ role = $role; text = $text.Trim() }
    }

    $isCorrection = ($type -ne 'control')
    $case = [ordered]@{ id = $id; type = $type }
    if ($isCorrection) { $case['strength'] = "$($row.strength)".Trim() }
    $case['correction'] = $isCorrection
    if ("$($row.adviceRequested)".Trim().ToUpper() -eq 'TRUE') { $case['adviceRequested'] = $true }
    $case['turns'] = $turns
    $cases += $case
}

$doc = [ordered]@{ version = $Version; cases = $cases }
$json = $doc | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($Out, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "변환 완료: $($cases.Count)건 -> $Out"

$ErrorActionPreference = 'Stop'
$global:ProbeCase = 'one'
$global:DockerCalls = @()
$global:ProbePayload = ''
function global:docker {
    $arguments = @($args)
    $global:DockerCalls += ,$arguments
    $global:LASTEXITCODE = 0
    switch ($arguments[0]) {
        'ps' {
            if ($global:ProbeCase -eq 'none') { return }
            if ($global:ProbeCase -eq 'multiple') { return @('aaaaaaaaaaaa', 'bbbbbbbbbbbb') }
            return 'aaaaaaaaaaaa'
        }
        'inspect' {
            if ($global:ProbeCase -eq 'wrong_service') { return '{"com.docker.compose.service":"enrollment"}' }
            return '{"com.docker.compose.service":"otp"}'
        }
        'exec' {
            $global:ProbePayload = ($input | Out-String)
            if ($global:ProbeCase -eq 'db_failure') { $global:LASTEXITCODE = 1 }
            return '{"database":"synthetic_read_only"}'
        }
        default { throw 'Unexpected Docker mutation or command.' }
    }
}
try {
    $script = Join-Path $PSScriptRoot 'fin25-preflight.ps1'
    foreach ($case in @('one', 'none', 'multiple', 'wrong_service', 'db_failure')) {
        $global:ProbeCase = $case
        $global:DockerCalls = @()
        $failed = $false
        try {
            if ($case -eq 'wrong_service') { & $script -ContainerId 'aaaaaaaaaaaa' | Out-Null }
            else { & $script | Out-Null }
        } catch { $failed = $true }
        if ($failed -ne ($case -ne 'one')) { throw "Unexpected result for $case" }
        $executions = @($global:DockerCalls | Where-Object { $_[0] -eq 'exec' })
        if ($case -in @('none', 'multiple', 'wrong_service') -and $executions.Count -ne 0) {
            throw 'Ambiguous or wrong service must not be probed.'
        }
    }
    $global:ProbeCase = 'one'
    $global:DockerCalls = @()
    & $script -ContainerId 'aaaaaaaaaaaa' | Out-Null
    if (@($global:DockerCalls | Where-Object { $_[0] -eq 'ps' }).Count -ne 0) { throw 'Explicit ID should skip discovery.' }
    if (-not $global:ProbePayload.Contains('BEGIN READ ONLY')) { throw 'Missing read-only transaction.' }
    if ($global:ProbePayload -match '\b(INSERT|UPDATE|DELETE|TRUNCATE)\b') { throw 'Probe contains a write operation.' }
    $failed = $false
    try { & $script -ContainerId 'name;not-an-id' | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw 'Invalid container ID accepted.' }
    $global:ProbePayload | node --check --input-type=module
    if ($LASTEXITCODE -ne 0) { throw 'Embedded Node probe syntax is invalid.' }
    Write-Output 'PASS: 7 container selection/error scenarios; read-only SQL and Node syntax checks. No live Docker used.'
} finally { Remove-Item Function:\docker }

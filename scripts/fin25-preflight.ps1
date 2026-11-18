[CmdletBinding()]
param([string]$ContainerId)
$ErrorActionPreference = 'Stop'

# Read-only: no compose up/down, no enrollment, no queue mutations, no SMS.
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is not available in this PowerShell session.'
}
if (-not $ContainerId) {
    $candidates = @(docker ps --filter 'label=com.docker.compose.service=otp' --format '{{.ID}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect running Docker services.' }
    if ($candidates.Count -ne 1) {
        throw 'Expected one running Compose otp service. Select the correct container locally and rerun with -ContainerId <id>.'
    }
    $ContainerId = $candidates[0].Trim()
}
if ($ContainerId -notmatch '^[a-fA-F0-9]{12,64}$') { throw 'Use a Docker container ID, not a name or command.' }
$labelsJson = docker inspect --format '{{json .Config.Labels}}' $ContainerId
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the selected container labels.' }
$labels = $labelsJson | ConvertFrom-Json
if ($labels.'com.docker.compose.service' -ne 'otp') { throw 'The selected container is not the Compose otp service.' }
Write-Output 'FIN25 read-only preflight: flags, queue counts and device status only.'

$probe = @'
import pg from 'pg';
const { Pool } = pg;
const flag = name => process.env[name] === 'true' ? true : process.env[name] === 'false' ? false : 'unset_or_invalid';
const result = {
  checkedAtUtc: new Date().toISOString(),
  flags: { OTP_ENABLED: flag('OTP_ENABLED'), FIN25_SMS_DISPATCH_ENABLED: flag('FIN25_SMS_DISPATCH_ENABLED') },
  database: 'not_checked',
};
const pool = new Pool({ connectionString: process.env.DATABASE_URL, connectionTimeoutMillis: 5000, statement_timeout: 5000 });
let client;
try {
  client = await pool.connect();
  await client.query('BEGIN READ ONLY');
  result.queue = (await client.query(`SELECT
    count(*) FILTER (WHERE j.status='pending' AND c.expires_at>now() AND j.attempts<3)::int AS pending_sendable,
    count(*) FILTER (WHERE j.status='pending' AND c.expires_at<=now())::int AS pending_expired,
    count(*) FILTER (WHERE j.status='leased')::int AS leased_needing_review,
    count(*) FILTER (WHERE j.status='sent')::int AS sent,
    count(*) FILTER (WHERE j.status='failed')::int AS failed,
    count(*) FILTER (WHERE j.status='delivered')::int AS delivered
    FROM fin25_sms_jobs j JOIN fin25_otp_challenges c ON c.id=j.challenge_id`)).rows[0];
  result.devices = (await client.query(`SELECT status, count(*)::int AS count,
    max(last_seen_at) AS latest_heartbeat_utc FROM fin25_gateway_devices GROUP BY status ORDER BY status`)).rows;
  await client.query('ROLLBACK');
  result.database = 'read_ok';
} catch (error) {
  // Never expose connection strings, database messages, numbers, OTPs or keys.
  result.database = 'read_failed';
  result.errorCode = /^[A-Z0-9_]{2,32}$/.test(error.code || '') ? error.code : 'CHECK_FAILED';
  if (client) { try { await client.query('ROLLBACK'); } catch {} }
  process.exitCode = 1;
} finally {
  client?.release();
  await pool.end();
  console.log(JSON.stringify(result, null, 2));
}
'@
$probe | docker exec -i -w /app $ContainerId node --input-type=module
if ($LASTEXITCODE -ne 0) { throw 'Preflight could not complete. Share only the safe JSON result above.' }
Write-Output 'Completed. No settings changed and no SMS requested. A sendable queue is NOT a single-message test guarantee.'

# Wipes the local demo database and reseeds the demo data for showing TurfUp to turf owners (Windows PowerShell
# version of reset-demo.sh): admin 9999999999 / admin-demo-123, owner 8888888888 / owner-demo-123, approved venue
# "Kick Arena", and today's and tomorrow's bookings there (see backend DemoShowcase). Bookings are placed relative
# to now, so run it shortly before a demo. -Empty seeds the venue without bookings.
# Only touches the docker-compose `postgres` service (port 5432). Stop the backend first.
param([switch]$Empty)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$running = $false
try {
    Invoke-WebRequest -Uri 'http://localhost:8080/health' -TimeoutSec 2 -UseBasicParsing | Out-Null
    $running = $true
} catch [System.Net.WebException] {
    # Any HTTP answer (even an error status) means something is serving on :8080.
    $running = $null -ne $_.Exception.Response
}
if ($running) {
    Write-Error 'The backend is running on :8080. Stop it first, then run this again.'
    exit 1
}

Write-Host 'Starting Postgres...'
docker compose up -d --wait postgres
if (-not $?) { exit 1 }

Write-Host 'Wiping the demo database...'
docker compose exec -T postgres psql -U turfup -d turfup -v ON_ERROR_STOP=1 -q -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
if (-not $?) { exit 1 }

Write-Host 'Migrating and seeding demo data...'
Set-Location (Join-Path $root 'backend')
$env:JWT_SECRET = "seed-only-$(Get-Random)-not-used-for-serving-requests"
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:TURFUP_DEMO_SHOWCASE = if ($Empty) { 'false' } else { 'true' }
& .\mvnw.cmd -q spring-boot:run '-Dspring-boot.run.arguments=--turfup.demo.seed-only'
if (-not $?) { exit 1 }

Write-Host ''
Write-Host 'Demo data is fresh:'
Write-Host '  admin  9999999999 / admin-demo-123'
Write-Host '  owner  8888888888 / owner-demo-123  (Kick Arena, Cidco)'
if ($Empty) {
    Write-Host '  players register in the app'
} else {
    Write-Host '  players 7000000001-7000000008 / player-demo-123  (Rahul Patil: regular, advance waived; Sameer Khan: regular)'
}

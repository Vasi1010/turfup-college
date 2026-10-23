// Starts the backend for the Playwright walkthrough against its own throwaway database:
// recreates the in-memory `postgres-e2e` container (port 5433), then runs Spring Boot on port 8081 with the
// demo profile so the e2e database gets the same seed data. Never points at the demo database (5432).
import { execSync, spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const windows = process.platform === 'win32'

execSync('docker compose --profile e2e up -d --force-recreate --wait postgres-e2e', { cwd: root, stdio: 'inherit' })

// Absolute path: some Windows setups disable running commands from the current directory.
const mvnw = resolve(root, 'backend', windows ? 'mvnw.cmd' : 'mvnw')
const backend = spawn(mvnw, ['-q', 'spring-boot:run'], {
  cwd: resolve(root, 'backend'),
  stdio: 'inherit',
  shell: windows,
  env: {
    ...process.env,
    SPRING_PROFILES_ACTIVE: 'demo',
    // The tournament module, with the demo tournament (tournament.spec.ts).
    TURFUP_TOURNAMENTS_ENABLED: 'true',
    DB_URL: 'jdbc:postgresql://localhost:5433/turfup_e2e',
    DB_USER: 'turfup',
    DB_PASSWORD: 'turfup',
    SERVER_PORT: '8081',
    JWT_SECRET: 'e2e-only-secret-not-used-anywhere-else-0123456789',
  },
})

const stop = () => backend.kill()
process.on('SIGINT', stop)
process.on('SIGTERM', stop)
backend.on('exit', (code) => process.exit(code ?? 0))

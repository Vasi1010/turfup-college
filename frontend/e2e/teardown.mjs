// Stops the throwaway e2e database after the walkthrough. Its data lives in memory, so it is gone too.
import { execSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export default function teardown() {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
  execSync('docker compose --profile e2e stop postgres-e2e', { cwd: root, stdio: 'ignore' })
}

import { useSyncExternalStore } from 'react'
import type { AuthResult, Role } from './types'

/**
 * The logged-in user, kept in localStorage so a refresh keeps you logged in.
 * (Business version: move the token to an httpOnly cookie; see CLAUDE.md.)
 */
export interface Session {
  token: string
  userId: number
  name: string
  role: Role
}

const KEY = 'turfup.session'
const listeners = new Set<() => void>()

function read(): Session | null {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

let current: Session | null = read()

function emit() {
  for (const l of listeners) l()
}

export function getSession(): Session | null {
  return current
}

export function setSession(auth: AuthResult) {
  current = { token: auth.token, userId: auth.userId, name: auth.name, role: auth.role }
  try {
    localStorage.setItem(KEY, JSON.stringify(current))
  } catch {
    // Private mode or storage blocked: the session lasts until the tab closes.
  }
  emit()
}

export function clearSession() {
  current = null
  try {
    localStorage.removeItem(KEY)
  } catch {
    // ignore
  }
  emit()
}

export function useSession(): Session | null {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => current,
  )
}

/** Where each role lands after logging in. */
export function homeFor(role: Role | undefined): string {
  if (role === 'OWNER') return '/owner'
  if (role === 'ADMIN') return '/admin'
  return '/'
}

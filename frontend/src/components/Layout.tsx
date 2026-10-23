import { CalendarDays, ClipboardList, Compass, LayoutGrid, ShieldCheck, Settings2, Trophy, UserRound, Users } from 'lucide-react'
import type { ComponentType, ReactNode } from 'react'
import { Link, Navigate, NavLink, Outlet, useLocation } from 'react-router'
import { useBackendWaking } from '../lib/backend'
import { homeFor, useSession } from '../lib/session'
import type { Role } from '../lib/types'
import { PolicyLinks } from '../pages/PolicyPage'
import { s } from '../strings'
import { CenterCircle } from './ui'

interface NavItem {
  to: string
  label: string
  icon: ComponentType<{ className?: string }>
  end?: boolean
}

function navFor(role: Role | undefined): NavItem[] {
  if (role === 'OWNER') {
    return [
      { to: '/owner', label: s.nav.today, icon: LayoutGrid, end: true },
      { to: '/owner/regulars', label: s.nav.regulars, icon: Users },
      { to: '/owner/venue', label: s.nav.venue, icon: Settings2 },
      { to: '/account', label: s.nav.account, icon: UserRound },
    ]
  }
  if (role === 'ADMIN') {
    return [
      { to: '/admin', label: s.nav.approvals, icon: ShieldCheck, end: true },
      { to: '/admin/tournaments', label: s.nav.cricket, icon: Trophy },
      { to: '/', label: s.nav.explore, icon: Compass, end: true },
      { to: '/account', label: s.nav.account, icon: UserRound },
    ]
  }
  return [
    { to: '/', label: s.nav.explore, icon: Compass, end: true },
    { to: '/bookings', label: s.nav.bookings, icon: role ? ClipboardList : CalendarDays },
    { to: role ? '/account' : '/login', label: role ? s.nav.account : s.auth.login, icon: UserRound },
  ]
}

export function Logo() {
  return (
    <Link to="/" className="flex items-center gap-2" aria-label={s.app.name}>
      <CenterCircle className="size-7 animate-flicker text-flood drop-shadow-[0_0_8px_rgb(214_255_61/0.7)]" />
      <span className="font-display text-[1.7rem] leading-none font-black tracking-wide uppercase">
        Turf<span className="text-flood">Up</span>
      </span>
    </Link>
  )
}

export function AppShell() {
  const session = useSession()
  const items = navFor(session?.role)
  const waking = useBackendWaking()
  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-40 border-b border-line bg-pitch/85 backdrop-blur-md">
        <div className="mx-auto flex h-14 max-w-2xl items-center justify-between px-4">
          <Logo />
          <span className="text-xs font-semibold uppercase tracking-[0.2em] text-chalk-dim">{s.app.city}</span>
        </div>
      </header>

      {waking && (
        <div role="status" className="border-b border-flood/30 bg-flood/10">
          <div className="mx-auto flex max-w-2xl items-start gap-3 px-4 py-3">
            <span className="mt-1 size-2.5 shrink-0 animate-pulse rounded-full bg-flood" aria-hidden />
            <div>
              <p className="text-sm font-semibold text-chalk">{s.wake.title}</p>
              <p className="text-xs text-chalk-dim">{s.wake.hint}</p>
            </div>
          </div>
        </div>
      )}

      <main className="mx-auto max-w-2xl px-4 pt-5 pb-32">
        <Outlet />
        <PolicyLinks />
      </main>

      <nav
        aria-label="Main"
        className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-pitch/92 pb-[env(safe-area-inset-bottom)] backdrop-blur-md"
      >
        <ul className="mx-auto flex max-w-2xl">
          {items.map(({ to, label, icon: Icon, end }) => (
            <li key={to} className="flex-1">
              <NavLink
                to={to}
                end={end}
                className={({ isActive }) =>
                  `flex min-h-16 flex-col items-center justify-center gap-1 text-[0.7rem] font-semibold uppercase tracking-wider transition ${
                    isActive ? 'text-flood' : 'text-chalk-faint hover:text-chalk-dim'
                  }`
                }
              >
                {({ isActive }) => (
                  <>
                    <Icon className={`size-5 ${isActive ? 'drop-shadow-[0_0_6px_rgb(214_255_61/0.8)]' : ''}`} />
                    {label}
                  </>
                )}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
    </div>
  )
}

/** Renders children only for the given roles; sends everyone else to log in or to their own home. */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const session = useSession()
  const location = useLocation()
  if (!session) {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }
  if (!roles.includes(session.role)) return <Navigate to={homeFor(session.role)} replace />
  return <>{children}</>
}

import { LogOut, Trophy } from 'lucide-react'
import { Link } from 'react-router'
import { Button, PageTitle } from '../components/ui'
import { clearSession, useSession } from '../lib/session'
import { s } from '../strings'

export function Account() {
  const session = useSession()
  if (!session) return null

  return (
    <div>
      <PageTitle kicker={s.auth.role[session.role]}>{session.name}</PageTitle>
      <Link
        to="/score"
        className="mb-4 flex min-h-12 items-center gap-2 rounded-xl border border-line bg-turf px-5 font-semibold hover:bg-turf-2"
      >
        <Trophy className="size-4" aria-hidden /> {s.score.accountLink}
      </Link>
      <Button
        variant="secondary"
        className="w-full"
        onClick={() => {
          // A full reload drops every cached query, and leaves before this guarded page can redirect to login.
          clearSession()
          window.location.replace('/')
        }}
      >
        <LogOut className="size-4" aria-hidden /> {s.auth.logout}
      </Button>
    </div>
  )
}

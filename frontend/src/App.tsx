import { createBrowserRouter, Navigate, RouterProvider, useLocation, useParams } from 'react-router'
import { AppShell, RequireRole } from './components/Layout'
import { EmptyState } from './components/ui'
import { Account } from './pages/Account'
import { AdminApprovals } from './pages/admin/AdminApprovals'
import { AdminTournament } from './pages/admin/AdminTournament'
import { AdminTournaments } from './pages/admin/AdminTournaments'
import { Login } from './pages/Login'
import { PolicyPage } from './pages/PolicyPage'
import { OwnerToday } from './pages/owner/OwnerToday'
import { Regulars } from './pages/owner/Regulars'
import { VenueSetup } from './pages/owner/VenueSetup'
import { Explore } from './pages/player/Explore'
import { HoldPage } from './pages/player/HoldPage'
import { MyBookings } from './pages/player/MyBookings'
import { VenuePage } from './pages/player/VenuePage'
import { ScorerHome } from './pages/scorer/ScorerHome'
import { ScorerMatch } from './pages/scorer/ScorerMatch'
import { MatchPage } from './pages/tournament/MatchPage'
import { TournamentList } from './pages/tournament/TournamentList'
import { TournamentPage } from './pages/tournament/TournamentPage'
import { homeFor, useSession } from './lib/session'
import { s } from './strings'

function Home() {
  const session = useSession()
  if (session?.role === 'OWNER') return <Navigate to={homeFor(session.role)} replace />
  return <Explore />
}

/** Links from before venues had courts: turf n became venue n. */
function OldTurfLink() {
  const { turfId } = useParams()
  const { search } = useLocation()
  return <Navigate to={`/venues/${turfId}${search}`} replace />
}

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <Home /> },
      { path: 'venues/:venueId', element: <VenuePage /> },
      { path: 'turfs/:turfId', element: <OldTurfLink /> },
      { path: 'login', element: <Login /> },
      { path: 'account', element: <RequireRole roles={['PLAYER', 'OWNER', 'ADMIN']}><Account /></RequireRole> },
      { path: 'hold/:bookingId', element: <RequireRole roles={['PLAYER']}><HoldPage /></RequireRole> },
      { path: 'bookings', element: <RequireRole roles={['PLAYER']}><MyBookings /></RequireRole> },
      { path: 'owner', element: <RequireRole roles={['OWNER']}><OwnerToday /></RequireRole> },
      { path: 'owner/regulars', element: <RequireRole roles={['OWNER']}><Regulars /></RequireRole> },
      { path: 'owner/venue', element: <RequireRole roles={['OWNER']}><VenueSetup /></RequireRole> },
      { path: 'owner/venue/new', element: <RequireRole roles={['OWNER']}><VenueSetup isNew /></RequireRole> },
      { path: 'admin', element: <RequireRole roles={['ADMIN']}><AdminApprovals /></RequireRole> },
      { path: 'admin/tournaments', element: <RequireRole roles={['ADMIN']}><AdminTournaments /></RequireRole> },
      { path: 'admin/tournaments/:tournamentId', element: <RequireRole roles={['ADMIN']}><AdminTournament /></RequireRole> },
      // Tournament pages are public: the QR posters at the ground point to /t/{id}.
      { path: 'tournaments', element: <TournamentList /> },
      { path: 't/:tournamentId', element: <TournamentPage /> },
      { path: 'm/:matchId', element: <MatchPage /> },
      // Anyone logged in may open these; the server lets only a match's scorer (or an admin) score it.
      { path: 'score', element: <RequireRole roles={['PLAYER', 'OWNER', 'ADMIN']}><ScorerHome /></RequireRole> },
      { path: 'score/:matchId', element: <RequireRole roles={['PLAYER', 'OWNER', 'ADMIN']}><ScorerMatch /></RequireRole> },
      { path: 'terms', element: <PolicyPage which="terms" /> },
      { path: 'privacy', element: <PolicyPage which="privacy" /> },
      { path: 'refunds', element: <PolicyPage which="refunds" /> },
      { path: 'contact', element: <PolicyPage which="contact" /> },
      { path: '*', element: <EmptyState title={s.errors.notFound} /> },
    ],
  },
])

export default function App() {
  return <RouterProvider router={router} />
}

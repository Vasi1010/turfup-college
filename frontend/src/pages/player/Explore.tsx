import { useQuery } from '@tanstack/react-query'
import { ArrowUpRight, MapPin, Trophy } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { EmptyState, ErrorState, Spinner } from '../../components/ui'
import { api } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import { money } from '../../lib/format'
import type { AreaCount, SportCount, VenueCard } from '../../lib/types'
import type { TournamentSummary } from '../../lib/tournamentTypes'
import { s } from '../../strings'

/**
 * Live cricket: a link to the latest tournament (or the list when there are several). Hidden when there are none,
 * or when the tournament module is switched off (the endpoint answers 404).
 */
function TournamentBanner() {
  const tournaments = useQuery({
    queryKey: ['tournaments'],
    queryFn: () => api<TournamentSummary[]>('/api/tournaments'),
    retry: false,
  })
  const list = tournaments.data
  if (!list || list.length === 0) return null
  return (
    <Link
      to={list.length === 1 ? `/t/${list[0].id}` : '/tournaments'}
      className="mb-5 flex items-center gap-3 rounded-2xl border border-flood/40 bg-turf p-4 transition hover:bg-turf-2"
    >
      <Trophy className="size-5 shrink-0 text-flood" aria-hidden />
      <span className="min-w-0 flex-1">
        <span className="block font-semibold">{s.cup.banner(list[0].name)}</span>
        <span className="block text-xs text-chalk-dim">{s.cup.bannerHint}</span>
      </span>
      <ArrowUpRight className="size-4 text-chalk-dim" aria-hidden />
    </Link>
  )
}

/** Browse venues by sport and area. Both filters live in the URL (?sport=&area=). */
export function Explore() {
  const [params, setParams] = useSearchParams()
  const area = params.get('area') ?? ''
  const sport = params.get('sport') ?? ''

  const areas = useQuery({ queryKey: ['areas'], queryFn: () => api<AreaCount[]>('/api/venues/areas') })
  const sports = useQuery({ queryKey: ['sports'], queryFn: () => api<SportCount[]>('/api/venues/sports') })
  const venues = useQuery({
    queryKey: ['venues', area, sport],
    queryFn: () => api<VenueCard[]>('/api/venues', { query: { area, sport } }),
  })

  const setFilter = (key: 'area' | 'sport', value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next)
  }

  return (
    <div>
      <section className="mb-6 animate-rise">
        <p className="text-xs font-semibold uppercase tracking-[0.22em] text-flood">{s.app.city}</p>
        <h1 className="mt-1 font-display text-[3.2rem] leading-[0.85] font-black uppercase">
          {s.app.tagline}
        </h1>
      </section>

      <TournamentBanner />

      <ChipRow label={s.venue.sport}>
        <Chip label={s.explore.allSports} active={!sport} onClick={() => setFilter('sport', '')} />
        {sports.data?.filter((sp) => sp.venues > 0).map((sp) => (
          <Chip
            key={sp.sport}
            label={s.sports[sp.sport] ?? sp.sport}
            count={sp.venues}
            active={sport === sp.sport}
            onClick={() => setFilter('sport', sp.sport)}
          />
        ))}
      </ChipRow>
      <ChipRow label={s.explore.allAreas}>
        <Chip label={s.explore.allAreas} active={!area} onClick={() => setFilter('area', '')} />
        {areas.data?.map((a) => (
          <Chip
            key={a.area}
            label={a.area}
            count={a.venues}
            active={area.toLowerCase() === a.area.toLowerCase()}
            onClick={() => setFilter('area', a.area)}
          />
        ))}
      </ChipRow>

      {venues.isPending && <Spinner />}
      {venues.isError && <ErrorState message={errorMessage(venues.error)} onRetry={() => venues.refetch()} />}
      {venues.data?.length === 0 && <EmptyState title={s.explore.empty} hint={s.explore.emptyHint} />}

      <ul className="space-y-3">
        {venues.data?.map((v, i) => (
          <li key={v.id} className="animate-rise" style={{ animationDelay: `${60 + i * 50}ms` }}>
            <VenueCardView venue={v} sport={sport} />
          </li>
        ))}
      </ul>
    </div>
  )
}

function ChipRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="-mx-4 mb-3 overflow-x-auto px-4 [scrollbar-width:none]">
      <div className="flex gap-2" role="group" aria-label={label}>{children}</div>
    </div>
  )
}

function Chip({ label, count, active, onClick }: { label: string; count?: number; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      aria-pressed={active}
      className={`flex min-h-10 shrink-0 items-center gap-2 rounded-full border px-4 text-sm font-semibold transition ${
        active ? 'border-flood bg-flood text-flood-ink' : 'border-line bg-pitch-2/70 text-chalk hover:border-chalk-faint'
      }`}
    >
      {label}
      {count !== undefined && <span className={`num text-base ${active ? '' : 'text-chalk-faint'}`}>{count}</span>}
    </button>
  )
}

function VenueCardView({ venue, sport }: { venue: VenueCard; sport: string }) {
  return (
    <Link
      to={`/venues/${venue.id}${sport ? `?sport=${encodeURIComponent(sport)}` : ''}`}
      className="group relative block overflow-hidden rounded-2xl border border-line bg-gradient-to-br from-turf to-pitch-2 p-5 transition hover:border-flood/50"
    >
      {/* Penalty-box markings in the corner. */}
      <svg aria-hidden viewBox="0 0 120 120" className="absolute -top-6 -right-6 size-32 text-line transition group-hover:text-flood/30" fill="none" stroke="currentColor" strokeWidth="2.5">
        <rect x="30" y="0" width="90" height="70" />
        <rect x="65" y="0" width="55" height="32" />
        <path d="M30 70 a 30 30 0 0 0 0 -50" transform="translate(0 25)" />
      </svg>

      <div className="relative">
        <p className="flex items-center gap-1 text-xs font-semibold uppercase tracking-[0.16em] text-chalk-dim">
          <MapPin className="size-3.5" aria-hidden /> {venue.area}
        </p>
        <h2 className="mt-1 font-display text-3xl leading-none font-extrabold uppercase">{venue.name}</h2>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {venue.sports.map((sp) => (
            <span key={sp.sport} className={`rounded-md px-2 py-0.5 text-xs ${sp.sport === sport ? 'bg-flood/20 text-flood' : 'bg-pitch/60 text-chalk-dim'}`}>
              {s.explore.courts(s.sports[sp.sport] ?? sp.sport, sp.courts)}
            </span>
          ))}
        </div>

        <div className="mt-4 flex items-end justify-between">
          <div>
            {venue.fromRate !== null && (
              <p className="leading-none">
                <span className="text-xs text-chalk-dim">{s.common.from} </span>
                <span className="num text-4xl font-extrabold text-flood">{money(venue.fromRate)}</span>
                <span className="text-sm text-chalk-dim">{s.common.perHour}</span>
              </p>
            )}
            <p className="mt-1 text-xs text-chalk-faint">{s.explore.advance(money(venue.advanceFrom))}</p>
          </div>
          <span className="flex size-11 items-center justify-center rounded-full border border-line transition group-hover:border-flood group-hover:bg-flood group-hover:text-flood-ink">
            <ArrowUpRight className="size-5" aria-hidden />
          </span>
        </div>
      </div>
    </Link>
  )
}

import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { api } from '../../lib/api'
import type { VenueView } from '../../lib/types'

const KEY = 'turfup.ownerVenue'

function stored(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

/**
 * The owner's venues and the one they are working on. The choice is in the URL (?venue=) and remembered on
 * this device, so switching tabs keeps the same venue.
 */
export function useOwnerVenue() {
  const [params, setParams] = useSearchParams()
  const venues = useQuery({ queryKey: ['owner-venues'], queryFn: () => api<VenueView[]>('/api/owner/venues') })
  const wanted = Number(params.get('venue') ?? stored())
  const list = venues.data ?? []
  const venue = list.find((v) => v.id === wanted) ?? list[0] ?? null

  const select = (id: number) => {
    try {
      localStorage.setItem(KEY, String(id))
    } catch {
      // ignore
    }
    const next = new URLSearchParams(params)
    next.set('venue', String(id))
    next.delete('date')
    setParams(next, { replace: true })
  }

  return { venues, venue, select }
}

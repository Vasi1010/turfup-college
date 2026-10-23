import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError, NetworkError } from '../../lib/api'
import { errorMessage } from '../../lib/errors'
import type { AddDeliveryRequest, MatchDetail } from '../../lib/tournamentTypes'

/** A ball scored on this phone that the server hasn't confirmed yet. */
export interface Queued {
  inningsId: number
  ball: AddDeliveryRequest
}

export type QueueState =
  | { status: 'idle' | 'sending' | 'offline' }
  /** The server refused a ball (or the session expired): nothing more is sent until the scorer decides. */
  | { status: 'stuck'; message: string }

const RETRY_MS = 4000
const key = (matchId: number) => `turfup.ballQueue.${matchId}`

function load(matchId: number): Queued[] {
  try {
    const raw = localStorage.getItem(key(matchId))
    return raw ? (JSON.parse(raw) as Queued[]) : []
  } catch {
    return []
  }
}

function save(matchId: number, queue: Queued[]) {
  try {
    if (queue.length) localStorage.setItem(key(matchId), JSON.stringify(queue))
    else localStorage.removeItem(key(matchId))
  } catch {
    // Storage full or blocked: the queue still works in memory.
  }
}

/**
 * The offline queue (task B6): the balls scored on this phone and not yet confirmed, in order, saved on the phone so
 * a reload or a closed tab loses nothing. They are sent one at a time whenever there is a connection; each keeps its
 * id, so a ball the server already has (its answer was lost) is not recorded twice. A ball the server refuses stops
 * the queue until the scorer retries or discards what is left.
 */
export function useBallQueue(matchId: number, onConfirmed: (m: MatchDetail) => void) {
  const [items, setItems] = useState<Queued[]>(() => load(matchId))
  const [state, setState] = useState<QueueState>({ status: 'idle' })
  const itemsRef = useRef(items)
  const sending = useRef(false)
  const stuck = useRef(false)
  const retry = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const confirmed = useRef(onConfirmed)
  useEffect(() => {
    confirmed.current = onConfirmed
  }, [onConfirmed])

  const update = useCallback((change: (q: Queued[]) => Queued[]) => {
    const next = change(itemsRef.current)
    itemsRef.current = next
    save(matchId, next)
    setItems(next)
  }, [matchId])

  const drain = useCallback(async () => {
    if (sending.current || stuck.current) return
    clearTimeout(retry.current)
    sending.current = true
    try {
      while (itemsRef.current.length > 0) {
        const head = itemsRef.current[0]
        setState({ status: 'sending' })
        try {
          const m = await api<MatchDetail>(`/api/innings/${head.inningsId}/deliveries`, { method: 'POST', body: head.ball })
          update((q) => (q[0]?.ball.id === head.ball.id ? q.slice(1) : q))
          confirmed.current(m)
        } catch (e) {
          if (e instanceof NetworkError || (e instanceof ApiError && e.status >= 500)) {
            setState({ status: 'offline' })
            retry.current = setTimeout(() => void drain(), RETRY_MS)
            return
          }
          if (e instanceof ApiError && e.body && typeof e.body.match === 'object' && e.body.match) {
            confirmed.current(e.body.match as MatchDetail)
          }
          stuck.current = true
          setState({ status: 'stuck', message: errorMessage(e) })
          return
        }
      }
      setState({ status: 'idle' })
    } finally {
      sending.current = false
    }
  }, [update])

  // Send what is waiting: on opening (balls saved earlier), after each new ball, and when the connection returns.
  useEffect(() => {
    if (items.length > 0) void drain()
  }, [items.length, drain])
  useEffect(() => {
    const online = () => void drain()
    window.addEventListener('online', online)
    return () => {
      window.removeEventListener('online', online)
      clearTimeout(retry.current)
    }
  }, [drain])

  return {
    items,
    state,
    add: (inningsId: number, ball: AddDeliveryRequest) => update((q) => [...q, { inningsId, ball }]),
    /** Takes back the last ball if it hasn't left yet (the one on its way can only be undone once confirmed). */
    undoLast: (): boolean => {
      const q = itemsRef.current
      if (q.length === 0 || (sending.current && q.length === 1)) return false
      update((rest) => rest.slice(0, -1))
      return true
    },
    resume: () => {
      stuck.current = false
      setState({ status: 'idle' })
      void drain()
    },
    discard: () => {
      stuck.current = false
      update(() => [])
      setState({ status: 'idle' })
    },
  }
}

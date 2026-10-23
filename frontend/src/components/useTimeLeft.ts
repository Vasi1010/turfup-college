import { useEffect, useState } from 'react'
import { serverNow } from '../lib/api'

/** Milliseconds left until `deadline`, by the server's clock, ticking every 250 ms. 0 when there is none. */
export function useTimeLeft(deadline: string | null): number {
  const target = deadline ? new Date(deadline).getTime() : 0
  const [left, setLeft] = useState(() => target - serverNow())
  useEffect(() => {
    if (!deadline) return
    const id = setInterval(() => setLeft(target - serverNow()), 250)
    return () => clearInterval(id)
  }, [deadline, target])
  return deadline ? left : 0
}

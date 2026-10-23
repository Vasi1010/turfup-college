import { useQuery } from '@tanstack/react-query'
import { api } from './api'
import type { ClientConfig } from './types'

/** Server settings the UI depends on (payment mode). Fetched once per session. */
export function useAppConfig() {
  return useQuery({
    queryKey: ['config'],
    queryFn: () => api<ClientConfig>('/api/config'),
    staleTime: Infinity,
  })
}

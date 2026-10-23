import { createContext, useContext } from 'react'

export type ShowToast = (text: string, tone?: 'ok' | 'error') => void

export const ToastContext = createContext<ShowToast>(() => {})

/** Shows a short message at the top of the screen. */
export function useToast(): ShowToast {
  return useContext(ToastContext)
}

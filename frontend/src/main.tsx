import '@fontsource-variable/big-shoulders-display'
import '@fontsource-variable/instrument-sans'
import './index.css'

import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { ToastProvider } from './components/ui'
import { queryRetry, queryRetryDelay } from './lib/backend'
import { initMonitoring } from './lib/monitoring'

initMonitoring()

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: queryRetry, retryDelay: queryRetryDelay, staleTime: 15_000, refetchOnWindowFocus: true },
    mutations: { retry: false },
  },
  queryCache: new QueryCache(),
  mutationCache: new MutationCache(),
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <App />
      </ToastProvider>
    </QueryClientProvider>
  </StrictMode>,
)

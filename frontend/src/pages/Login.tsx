import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Navigate, useSearchParams } from 'react-router'
import { Button, InlineError, PageTitle, Switch } from '../components/ui'
import { api } from '../lib/api'
import { errorMessage } from '../lib/errors'
import { homeFor, setSession, useSession } from '../lib/session'
import type { AuthResult } from '../lib/types'
import { s } from '../strings'

type Mode = 'login' | 'register'

export function Login() {
  const session = useSession()
  const [params] = useSearchParams()
  const next = params.get('next')
  const [mode, setMode] = useState<Mode>('login')
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [owner, setOwner] = useState(false)

  const submit = useMutation({
    mutationFn: () =>
      mode === 'login'
        ? api<AuthResult>('/api/auth/login', { method: 'POST', body: { phone, password } })
        : api<AuthResult>('/api/auth/register', {
            method: 'POST',
            body: { name, phone, password, role: owner ? 'OWNER' : 'PLAYER' },
          }),
    // Logging in updates the session; the redirect below then sends the user on.
    onSuccess: setSession,
  })

  if (session) {
    // Only same-site paths, so a crafted link cannot send people elsewhere after login.
    const safeNext = next && next.startsWith('/') && !next.startsWith('//') ? next : null
    return <Navigate to={safeNext ?? homeFor(session.role)} replace />
  }

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    submit.mutate()
  }

  return (
    <div className="mx-auto max-w-sm">
      <PageTitle kicker={s.app.tagline}>{mode === 'login' ? s.auth.login : s.auth.register}</PageTitle>

      <div className="mb-6 grid grid-cols-2 rounded-xl border border-line bg-pitch-2 p-1" role="tablist">
        {(['login', 'register'] as Mode[]).map((m) => (
          <button
            key={m}
            role="tab"
            aria-selected={mode === m}
            onClick={() => {
              setMode(m)
              submit.reset()
            }}
            className={`min-h-11 rounded-lg text-sm font-semibold transition ${
              mode === m ? 'bg-turf-2 text-chalk' : 'text-chalk-dim hover:text-chalk'
            }`}
          >
            {m === 'login' ? s.auth.login : s.auth.register}
          </button>
        ))}
      </div>

      <form onSubmit={onSubmit} className="space-y-4 animate-rise">
        {mode === 'register' && (
          <div>
            <label className="label" htmlFor="name">{s.auth.name}</label>
            <input id="name" className="field" autoComplete="name" required maxLength={120}
                   value={name} onChange={(e) => setName(e.target.value)} />
          </div>
        )}
        <div>
          <label className="label" htmlFor="phone">{s.auth.phone}</label>
          <input id="phone" className="field num text-xl tracking-widest" inputMode="numeric" autoComplete="tel-national"
                 required pattern="[6-9][0-9]{9}" maxLength={10} placeholder="98xxxxxxxx"
                 value={phone} onChange={(e) => setPhone(e.target.value.replace(/\D/g, ''))} />
          <p className="mt-1 text-xs text-chalk-faint">{s.auth.phoneHint}</p>
        </div>
        <div>
          <label className="label" htmlFor="password">{s.auth.password}</label>
          <input id="password" type="password" className="field" required minLength={mode === 'register' ? 8 : 1}
                 autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                 value={password} onChange={(e) => setPassword(e.target.value)} />
          {mode === 'register' && <p className="mt-1 text-xs text-chalk-faint">{s.auth.passwordHint}</p>}
        </div>
        {mode === 'register' && (
          <div className="flex items-center justify-between gap-4 rounded-xl border border-line bg-pitch-2 px-4 py-3">
            <div>
              <p className="font-semibold">{s.auth.owner}</p>
              <p className="text-xs text-chalk-dim">{s.auth.ownerHint}</p>
            </div>
            <Switch checked={owner} onChange={setOwner} label={s.auth.owner} />
          </div>
        )}

        <InlineError message={submit.isError ? errorMessage(submit.error) : null} />

        <Button type="submit" className="w-full" busy={submit.isPending}>
          {mode === 'login' ? s.auth.login : s.auth.register}
        </Button>
      </form>
    </div>
  )
}

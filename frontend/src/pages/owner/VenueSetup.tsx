import { useMutation, useQueryClient } from '@tanstack/react-query'
import { CircleAlert, CircleCheck, Pencil, Plus, Trash2 } from 'lucide-react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { Button, Chip, InlineError, PageTitle, Sheet, Spinner } from '../../components/ui'
import { useToast } from '../../components/toast-context'
import { api } from '../../lib/api'
import { useAppConfig } from '../../lib/appConfig'
import { SPORTS } from '../../lib/config'
import { errorMessage } from '../../lib/errors'
import { minuteLabel, minutesToTime, money, timeToMinutes } from '../../lib/format'
import { closeMinute, coverageBar, endMinute, firstGap, overlaps, startMinute } from '../../lib/priceRules'
import type { CourtSetupRequest, CourtView, PriceRule, VenueSetupRequest, VenueView } from '../../lib/types'
import { s } from '../../strings'
import { VenuePicker } from './VenuePicker'
import { useOwnerVenue } from './useOwnerVenue'

interface RuleDraft {
  key: number
  dayOfWeek: number | null
  start: string
  end: string
  rate: string
}

let nextKey = 1

/** The usual Aurangabad turf tariff, pre-filled for a new court: ₹800/hr until 5 PM, ₹1,000/hr after. */
const DEFAULT_RULES: Omit<RuleDraft, 'key'>[] = [
  { dayOfWeek: null, start: '06:00', end: '17:00', rate: '800' },
  { dayOfWeek: null, start: '17:00', end: '02:00', rate: '1000' },
]

/** The venue (place, hours, refund allowance) and its courts. A new venue is saved with its first court. */
export function VenueSetup({ isNew = false }: { isNew?: boolean }) {
  const { venues, venue, select } = useOwnerVenue()
  const upiMode = useAppConfig().data?.paymentsMode === 'UPI'
  if (!isNew && venues.isPending) return <Spinner />
  if (!isNew && !venue) return <Navigate to="/owner/venue/new" replace />
  return (
    <>
      {!isNew && venues.data && venue && <VenuePicker venues={venues.data} value={venue.id} onChange={select} />}
      <VenueForm key={isNew ? 'new' : venue!.id} initial={isNew ? null : venue} onCreated={select} />
      {!isNew && venue && upiMode && <UpiSection key={`upi-${venue.id}`} venue={venue} />}
      {!isNew && venue && <CourtsSection key={`courts-${venue.id}`} venue={venue} />}
    </>
  )
}

function VenueForm({ initial, onCreated }: { initial: VenueView | null; onCreated: (id: number) => void }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const toast = useToast()

  const [name, setName] = useState(initial?.name ?? '')
  const [area, setArea] = useState(initial?.area ?? '')
  const [address, setAddress] = useState(initial?.address ?? '')
  const [opens, setOpens] = useState(minutesToTime(initial?.openMinute ?? 360))
  const [closes, setCloses] = useState(minutesToTime(initial?.closeMinute ?? 1560))
  const [refundsPerMonth, setRefundsPerMonth] = useState(String(initial?.refundsPerMonth ?? 1))
  const open = timeToMinutes(opens)
  const close = closeMinute(closes, open)
  // A new venue is created together with its first court.
  const firstCourt = useCourtDraft(null, open, close, closes)

  const save = useMutation({
    mutationFn: () => {
      const venue: VenueSetupRequest = {
        name, area, address, lat: initial?.lat ?? null, lng: initial?.lng ?? null,
        openMinute: open, closeMinute: close, refundsPerMonth: Number(refundsPerMonth),
      }
      return initial
        ? api<VenueView>(`/api/owner/venues/${initial.id}`, { method: 'PUT', body: venue })
        : api<VenueView>('/api/owner/venues', { method: 'POST', body: { venue, courts: [firstCourt.body] } })
    },
    onSuccess: (v) => {
      queryClient.invalidateQueries({ queryKey: ['owner-venues'] })
      queryClient.invalidateQueries({ queryKey: ['schedule'] })
      toast(s.setup.saved)
      if (!initial) {
        onCreated(v.id)
        navigate(`/owner?venue=${v.id}`)
      }
    },
  })

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate()
  }
  const blocked = !initial && !!firstCourt.problem

  return (
    <form onSubmit={onSubmit} className="space-y-5">
      <PageTitle>{initial ? s.setup.editTitle : s.setup.newTitle}</PageTitle>

      {initial && (
        <p className={`flex items-center gap-2 rounded-xl border px-4 py-3 text-sm ${initial.approved ? 'border-ok/40 text-ok' : 'border-amber/40 bg-amber/10 text-amber'}`}>
          {initial.approved ? <CircleCheck className="size-4" /> : <CircleAlert className="size-4" />}
          {initial.approved ? s.setup.approved : s.setup.notApproved}
        </p>
      )}

      <Card title={s.setup.basics}>
        <Field id="v-name" label={s.setup.name}>
          <input id="v-name" className="field" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field id="v-area" label={s.setup.area}>
          <input id="v-area" className="field" required maxLength={120} placeholder={s.setup.areaPlaceholder} value={area} onChange={(e) => setArea(e.target.value)} />
        </Field>
        <Field id="v-address" label={s.setup.address}>
          <input id="v-address" className="field" required value={address} onChange={(e) => setAddress(e.target.value)} />
        </Field>
      </Card>

      <Card title={s.setup.hours}>
        <div className="grid grid-cols-2 gap-3">
          <Field id="v-opens" label={s.setup.opens}>
            <input id="v-opens" type="time" className="field num text-xl" required value={opens} onChange={(e) => setOpens(e.target.value)} />
          </Field>
          <Field id="v-closes" label={`${s.setup.closes}${close >= 1440 ? ` · ${s.setup.nextDay}` : ''}`}>
            <input id="v-closes" type="time" className="field num text-xl" required value={closes} onChange={(e) => setCloses(e.target.value)} />
          </Field>
        </div>
        {initial && initial.courts.length > 1 && <p className="text-xs text-chalk-faint">{s.setup.hoursAllCourts}</p>}
      </Card>

      <Card title={s.setup.refunds}>
        <Field id="v-refunds" label={s.setup.refundsPerMonth}>
          <input id="v-refunds" type="number" min={0} max={31} className="field num text-xl" required value={refundsPerMonth} onChange={(e) => setRefundsPerMonth(e.target.value)} />
        </Field>
      </Card>

      {!initial && (
        <section className="space-y-3">
          <h2 className="font-display text-2xl font-bold uppercase">{s.setup.firstCourt}</h2>
          <p className="text-sm text-chalk-dim">{s.setup.firstCourtHint}</p>
          {firstCourt.fields}
        </section>
      )}

      <div className="sticky bottom-20 z-20 space-y-2 rounded-2xl bg-pitch/90 p-2 backdrop-blur">
        <InlineError message={save.isError ? errorMessage(save.error) : !initial ? firstCourt.problem : null} />
        <Button type="submit" className="w-full" busy={save.isPending} disabled={blocked}>
          {s.common.save}
        </Button>
      </div>
    </form>
  )
}

/** UPI mode: the UPI ID players pay this venue's advances to, and the name shown on the payment. */
function UpiSection({ venue }: { venue: VenueView }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [upiId, setUpiId] = useState(venue.upiId ?? '')
  const [upiName, setUpiName] = useState(venue.upiName ?? '')
  const valid = upiId.trim() === '' || /^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9.-]{1,63}$/.test(upiId.trim())

  const save = useMutation({
    mutationFn: () => api<VenueView>(`/api/owner/venues/${venue.id}/upi`, { method: 'PUT', body: { upiId, upiName } }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['owner-venues'] })
      toast(s.setup.upiSaved)
    },
  })

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    save.mutate()
  }

  return (
    <form onSubmit={onSubmit} className="mt-8 space-y-3">
      <Card title={s.setup.upiTitle}>
        <p className="text-sm text-chalk-dim">{s.setup.upiHint}</p>
        <Field id="v-upi-id" label={s.setup.upiId}>
          <input id="v-upi-id" className="field num" autoComplete="off" maxLength={100} placeholder={s.setup.upiIdPlaceholder}
            value={upiId} onChange={(e) => setUpiId(e.target.value)} aria-invalid={!valid} />
        </Field>
        {!valid && <p className="text-xs text-red">{s.setup.upiInvalid}</p>}
        <Field id="v-upi-name" label={s.setup.upiName}>
          <input id="v-upi-name" className="field" maxLength={60} placeholder={venue.name}
            value={upiName} onChange={(e) => setUpiName(e.target.value)} />
        </Field>
        <p className="text-xs text-chalk-faint">{s.setup.upiNameHint}</p>
        <InlineError message={save.isError ? errorMessage(save.error) : null} />
        <Button type="submit" variant="secondary" className="w-full" busy={save.isPending} disabled={!valid}>
          {s.setup.upiSave}
        </Button>
      </Card>
    </form>
  )
}

/** Existing venue: its courts, each editable in a sheet, plus "Add a court". */
function CourtsSection({ venue }: { venue: VenueView }) {
  const [editing, setEditing] = useState<CourtView | 'new' | null>(null)
  return (
    <section className="mt-8 space-y-3 pb-24">
      <div className="flex items-end justify-between">
        <h2 className="font-display text-2xl font-bold uppercase">{s.setup.courts}</h2>
        <Button variant="secondary" className="min-h-10 text-sm" onClick={() => setEditing('new')}>
          <Plus className="size-4" /> {s.setup.addCourt}
        </Button>
      </div>
      <ul className="space-y-2">
        {venue.courts.map((c) => (
          <li key={c.id}>
            <button onClick={() => setEditing(c)}
                    className="flex w-full items-center justify-between gap-3 rounded-2xl border border-line bg-pitch-2/70 p-4 text-left hover:border-chalk-faint">
              <div className="min-w-0">
                <p className="truncate text-lg font-semibold">{c.name}</p>
                <p className="truncate text-sm text-chalk-dim">
                  {c.sports.map((sp) => s.sports[sp] ?? sp).join(' · ')} · {s.setup.minutes(c.slotMinutes)} · {s.setup.advanceShort(money(c.advanceAmount))}
                </p>
              </div>
              <span className="flex shrink-0 items-center gap-2">
                {!c.active && <Chip tone="amber">{s.owner.paused}</Chip>}
                <Pencil className="size-4 text-chalk-faint" aria-label={s.setup.editCourt} />
              </span>
            </button>
          </li>
        ))}
      </ul>
      <Sheet open={editing !== null} onClose={() => setEditing(null)}
             title={editing === 'new' ? s.setup.newCourt : editing ? editing.name : ''}>
        {editing !== null && (
          <CourtEditor key={editing === 'new' ? 'new' : editing.id} venue={venue}
                       court={editing === 'new' ? null : editing} onDone={() => setEditing(null)} />
        )}
      </Sheet>
    </section>
  )
}

function CourtEditor({ venue, court, onDone }: { venue: VenueView; court: CourtView | null; onDone: () => void }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const draft = useCourtDraft(court, venue.openMinute, venue.closeMinute, minutesToTime(venue.closeMinute))
  const save = useMutation({
    mutationFn: () =>
      court
        ? api<VenueView>(`/api/owner/courts/${court.id}`, { method: 'PUT', body: draft.body })
        : api<VenueView>(`/api/owner/venues/${venue.id}/courts`, { method: 'POST', body: draft.body }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['owner-venues'] })
      queryClient.invalidateQueries({ queryKey: ['schedule'] })
      toast(s.setup.courtSaved)
      onDone()
    },
  })
  return (
    <form onSubmit={(e) => { e.preventDefault(); save.mutate() }} className="space-y-4">
      {draft.fields}
      <InlineError message={save.isError ? errorMessage(save.error) : draft.problem} />
      <Button type="submit" className="w-full" busy={save.isPending} disabled={!!draft.problem}>{s.common.save}</Button>
    </form>
  )
}

/**
 * The court form's state, fields and request body. {@code problem} is a client-side check that blocks saving
 * (a gap or overlap in the prices, a bad rate, no sport); the server re-checks everything.
 */
function useCourtDraft(initial: CourtView | null, open: number, close: number, closes: string) {
  const [name, setName] = useState(initial?.name ?? s.setup.defaultCourtName)
  const [sports, setSports] = useState<string[]>(initial?.sports ?? ['football'])
  const [slotMinutes, setSlotMinutes] = useState(initial?.slotMinutes ?? 60)
  const [advance, setAdvance] = useState(String((initial?.advanceAmount ?? 20_000) / 100))
  const [refundHours, setRefundHours] = useState(String(initial?.refundFullHoursBefore ?? 24))
  const [refundPolicy, setRefundPolicy] = useState(initial?.refundPolicy ?? '')
  const [active, setActive] = useState(initial?.active ?? true)
  const [rules, setRules] = useState<RuleDraft[]>(() =>
    (initial
      ? initial.priceRules.map((r) => ({
          dayOfWeek: r.dayOfWeek,
          start: minutesToTime(r.startMinute),
          end: minutesToTime(r.endMinute),
          rate: String(r.rate / 100),
        }))
      : DEFAULT_RULES
    ).map((r) => ({ ...r, key: nextKey++ })),
  )

  const priceRules: PriceRule[] = rules.map((r) => {
    const start = startMinute(r.start, open)
    return { dayOfWeek: r.dayOfWeek, startMinute: start, endMinute: endMinute(r.end, start), rate: Math.round(Number(r.rate) * 100) }
  })
  const gap = firstGap(priceRules, open, close)
  const clash = overlaps(priceRules)
  const badRate = priceRules.some((r) => !Number.isFinite(r.rate) || r.rate < 0)
  const problem = !sports.length ? s.setup.pickSport
    : gap !== null ? s.setup.gap(minuteLabel(gap))
      : clash.length ? s.setup.overlap
        : badRate ? s.errors.invalid : null

  const body: CourtSetupRequest = {
    name, sports, slotMinutes,
    advanceAmount: Math.round(Number(advance) * 100),
    refundFullHoursBefore: Number(refundHours),
    refundPolicy: refundPolicy || null,
    active,
    priceRules,
  }

  const updateRule = (key: number, patch: Partial<RuleDraft>) =>
    setRules((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r)))
  const removeRule = (key: number) => setRules((rs) => rs.filter((r) => r.key !== key))
  const addRule = (dayOfWeek: number | null) =>
    setRules((rs) => [...rs, { key: nextKey++, dayOfWeek, start: '17:00', end: closes, rate: dayOfWeek ? '1200' : '1000' }])
  const days = [...new Set(rules.filter((r) => r.dayOfWeek !== null).map((r) => r.dayOfWeek!))].sort()
  const id = initial ? `c${initial.id}` : 'cnew'

  const fields = (
    <>
      <Card title={s.setup.court}>
        <div className="grid grid-cols-2 gap-3">
          <Field id={`${id}-name`} label={s.setup.courtName}>
            <input id={`${id}-name`} className="field" required maxLength={60} placeholder={s.setup.courtNamePlaceholder}
                   value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field id={`${id}-slot`} label={s.setup.slotLength}>
            <select id={`${id}-slot`} className="field" value={slotMinutes} onChange={(e) => setSlotMinutes(Number(e.target.value))}>
              {[30, 60, 90, 120].map((m) => <option key={m} value={m}>{s.setup.minutes(m)}</option>)}
            </select>
          </Field>
        </div>
        <div>
          <p className="label">{s.setup.sports}</p>
          <div className="flex flex-wrap gap-2">
            {SPORTS.map((sp) => {
              const on = sports.includes(sp)
              return (
                <button type="button" key={sp} aria-pressed={on}
                        onClick={() => setSports(on ? sports.filter((x) => x !== sp) : [...sports, sp])}
                        className={`min-h-10 rounded-full border px-4 text-sm font-semibold ${on ? 'border-flood bg-flood text-flood-ink' : 'border-line'}`}>
                  {s.sports[sp]}
                </button>
              )
            })}
          </div>
          <p className="mt-1 text-xs text-chalk-faint">{s.setup.sharedPitchHint}</p>
        </div>
        {initial && (
          <label className="flex min-h-11 items-center gap-3 text-sm">
            <input type="checkbox" className="size-5 accent-flood" checked={!active} onChange={(e) => setActive(!e.target.checked)} />
            {s.setup.pause}
          </label>
        )}
      </Card>

      <Card title={s.setup.prices}>
        <CoverageBar rules={priceRules} open={open} close={close} />
        <p className={`flex items-center gap-1.5 text-sm ${gap === null ? 'text-ok' : 'text-red'}`}>
          {gap === null ? <CircleCheck className="size-4" /> : <CircleAlert className="size-4" />}
          {gap === null ? s.setup.coverageOk : s.setup.gap(minuteLabel(gap))}
        </p>
        {clash.length > 0 && <p className="flex items-center gap-1.5 text-sm text-red"><CircleAlert className="size-4" />{s.setup.overlap}</p>}

        <RuleGroup title={s.setup.allDays} rules={rules.filter((r) => r.dayOfWeek === null)} onChange={updateRule} onRemove={removeRule} />
        <Button type="button" variant="secondary" className="min-h-10 w-full text-sm" onClick={() => addRule(null)}>
          <Plus className="size-4" /> {s.setup.addRule}
        </Button>

        {days.map((d) => (
          <RuleGroup key={d} title={s.setup.days[d]} rules={rules.filter((r) => r.dayOfWeek === d)} onChange={updateRule} onRemove={removeRule}
                     day={d} onDay={(key, day) => updateRule(key, { dayOfWeek: day })} />
        ))}
        <Button type="button" variant="quiet" className="min-h-10 w-full text-sm" onClick={() => addRule(6)}>
          <Plus className="size-4" /> {s.setup.addDay}
        </Button>
      </Card>

      <Card title={s.setup.money}>
        <div className="grid grid-cols-2 gap-3">
          <Field id={`${id}-advance`} label={s.setup.advance}>
            <input id={`${id}-advance`} type="number" min={0} step={1} className="field num text-xl" required value={advance} onChange={(e) => setAdvance(e.target.value)} />
          </Field>
          <Field id={`${id}-refund-hours`} label={s.setup.refundHours}>
            <input id={`${id}-refund-hours`} type="number" min={0} max={720} className="field num text-xl" required value={refundHours} onChange={(e) => setRefundHours(e.target.value)} />
          </Field>
        </div>
        <Field id={`${id}-policy`} label={s.setup.refundPolicy}>
          <textarea id={`${id}-policy`} className="field min-h-20" value={refundPolicy} onChange={(e) => setRefundPolicy(e.target.value)} />
        </Field>
      </Card>
    </>
  )

  return { fields, body, problem }
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-3 rounded-2xl border border-line bg-pitch-2/70 p-4 animate-rise">
      <h2 className="font-display text-xl font-bold uppercase tracking-wide text-chalk-dim">{title}</h2>
      {children}
    </section>
  )
}

function Field({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  return (
    <div>
      <label className="label" htmlFor={id}>{label}</label>
      {children}
    </div>
  )
}

function RuleGroup({ title, rules, onChange, onRemove, day, onDay }: {
  title: string
  rules: RuleDraft[]
  onChange: (key: number, patch: Partial<RuleDraft>) => void
  onRemove: (key: number) => void
  day?: number
  onDay?: (key: number, day: number) => void
}) {
  return (
    <div className="space-y-2">
      <p className="label">{title}</p>
      {rules.map((r) => (
        <div key={r.key} className="grid grid-cols-[1fr_1fr_5rem_2.5rem] items-center gap-2">
          <input type="time" aria-label={`${title} start`} className="field num px-2 text-lg" value={r.start}
                 onChange={(e) => onChange(r.key, { start: e.target.value })} />
          <input type="time" aria-label={`${title} end`} className="field num px-2 text-lg" value={r.end}
                 onChange={(e) => onChange(r.key, { end: e.target.value })} />
          <input type="number" min={0} aria-label={s.setup.rate} className="field num px-2 text-lg" value={r.rate}
                 onChange={(e) => onChange(r.key, { rate: e.target.value })} />
          <button type="button" aria-label={s.setup.remove} onClick={() => onRemove(r.key)} className="flex size-10 items-center justify-center rounded-full text-chalk-faint hover:text-red">
            <Trash2 className="size-4" />
          </button>
          {day !== undefined && onDay && (
            <select aria-label={title} className="field col-span-4 py-2" value={day} onChange={(e) => onDay(r.key, Number(e.target.value))}>
              {[1, 2, 3, 4, 5, 6, 7].map((d) => <option key={d} value={d}>{s.setup.days[d]}</option>)}
            </select>
          )}
        </div>
      ))}
    </div>
  )
}

/** Opening hours as a strip: priced stretches are green, gaps are red. */
function CoverageBar({ rules, open, close }: { rules: PriceRule[]; open: number; close: number }) {
  if (close <= open) return null
  const parts = coverageBar(rules, open, close)
  return (
    <div>
      <div className="relative h-4 overflow-hidden rounded-full bg-pitch">
        {parts.map((p, i) => (
          <span key={i} className={`absolute inset-y-0 ${p.covered ? 'bg-turf-3' : 'bg-red/70'}`}
                style={{ left: `${p.from * 100}%`, width: `${(p.to - p.from) * 100}%` }} />
        ))}
      </div>
      <div className="mt-1 flex justify-between text-xs text-chalk-faint">
        <span className="num text-sm">{minuteLabel(open)}</span>
        <span className="num text-sm">{minuteLabel(close)}</span>
      </div>
    </div>
  )
}

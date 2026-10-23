import type { VenueView } from '../../lib/types'
import { s } from '../../strings'

/** Only shown to owners with more than one venue. */
export function VenuePicker({ venues, value, onChange }: { venues: VenueView[]; value: number; onChange: (id: number) => void }) {
  if (venues.length < 2) return null
  return (
    <label className="mb-4 block">
      <span className="label">{s.owner.pickVenue}</span>
      <select className="field" value={value} onChange={(e) => onChange(Number(e.target.value))}>
        {venues.map((v) => (
          <option key={v.id} value={v.id}>{v.name} · {v.area}</option>
        ))}
      </select>
    </label>
  )
}

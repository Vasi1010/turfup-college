import { Link } from 'react-router'
import { PageTitle } from '../components/ui'
import { BUSINESS } from '../lib/business'
import { s } from '../strings'
import { policies } from '../strings/policies.en'

export type PolicyKey = 'terms' | 'privacy' | 'refunds' | 'contact'

/** Business details still marked [FILL IN: ...] are highlighted so they cannot be missed before launch. */
function WithPlaceholders({ text }: { text: string }) {
  const parts = text.split(/(\[FILL IN:[^\]]*\])/)
  return (
    <>
      {parts.map((part, i) =>
        part.startsWith('[FILL IN') ? (
          <mark key={i} className="rounded bg-amber/20 px-1 text-amber">{part}</mark>
        ) : (
          part
        ),
      )}
    </>
  )
}

export function PolicyPage({ which }: { which: PolicyKey }) {
  const policy = policies(BUSINESS)[which]
  return (
    <article className="pb-4">
      <PageTitle kicker={s.legal.updated(BUSINESS.policiesUpdated)}>{policy.title}</PageTitle>
      <p className="mb-6 text-chalk-dim"><WithPlaceholders text={policy.intro} /></p>
      <div className="space-y-6">
        {policy.sections.map((section) => (
          <section key={section.heading}>
            <h2 className="mb-2 font-display text-xl font-bold uppercase tracking-wide">{section.heading}</h2>
            <div className="space-y-2 text-[0.95rem] leading-relaxed text-chalk-dim">
              {section.paragraphs.map((p) => (
                <p key={p}><WithPlaceholders text={p} /></p>
              ))}
            </div>
          </section>
        ))}
      </div>
    </article>
  )
}

const LINKS: { to: string; label: string }[] = [
  { to: '/terms', label: s.legal.terms },
  { to: '/privacy', label: s.legal.privacy },
  { to: '/refunds', label: s.legal.refunds },
  { to: '/contact', label: s.legal.contact },
]

/** Links to the policy pages, at the foot of every screen. */
export function PolicyLinks() {
  return (
    <footer className="mt-12 border-t border-line pt-4 text-xs text-chalk-faint">
      <nav aria-label="Policies">
        <ul className="flex flex-wrap gap-x-4 gap-y-2">
          {LINKS.map((l) => (
            <li key={l.to}>
              <Link to={l.to} className="hover:text-chalk-dim">{l.label}</Link>
            </li>
          ))}
        </ul>
      </nav>
    </footer>
  )
}

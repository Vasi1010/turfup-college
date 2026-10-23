import { en, type Strings } from './en'

/** Registered locales. Add `mr` / `hi` here once translated. */
const locales: Record<string, Strings> = { en }

export const locale = 'en'

/** The active strings. Import as `import { s } from '../strings'`. */
export const s: Strings = locales[locale]

export type { Strings }

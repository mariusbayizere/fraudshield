/** The UI languages, in the contract's `Locale` order (D-43); English is the default. */
export const LANGUAGES = ['en', 'rw', 'fr', 'sw'] as const;
export type Language = (typeof LANGUAGES)[number];
export const DEFAULT_LANGUAGE: Language = 'en';

/** Each language named in itself, so a user can find their own in any UI language. */
export const ENDONYM: Record<Language, string> = {
  en: 'English',
  rw: 'Ikinyarwanda',
  fr: 'Français',
  sw: 'Kiswahili',
};

/** Namespaces load lazily, per route (D-39). `common` and `designSystem` load at start. */
export const NAMESPACES = ['common', 'designSystem'] as const;
export type Namespace = (typeof NAMESPACES)[number];

export function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (LANGUAGES as readonly string[]).includes(value);
}

/** The first of the browser's preferred languages the console speaks, else English. */
export function preferredLanguage(browserLanguages: readonly string[]): Language {
  for (const tag of browserLanguages) {
    const base = tag.toLowerCase().split('-')[0];
    if (isLanguage(base)) return base;
  }
  return DEFAULT_LANGUAGE;
}

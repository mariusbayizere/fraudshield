import { isLanguage, type Language } from '../i18n/languages';

const LANGUAGE_KEY = 'fs.language';

/**
 * A viewer's language choice, kept in this browser only. Storage can be missing or throw (private
 * windows, blocked site data), so every access is guarded and the app works without it.
 */
export function rememberedLanguage(): Language | undefined {
  try {
    const value = localStorage.getItem(LANGUAGE_KEY);
    return isLanguage(value) ? value : undefined;
  } catch {
    return undefined;
  }
}

export function rememberLanguage(language: Language): void {
  try {
    localStorage.setItem(LANGUAGE_KEY, language);
  } catch {
    // The choice still applies for this session.
  }
}

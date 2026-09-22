import i18next, { type BackendModule, type i18n as I18n } from 'i18next';
import { initReactI18next } from 'react-i18next';
import { DEFAULT_LANGUAGE, LANGUAGES, NAMESPACES, type Language } from './languages';

type Catalogue = Record<string, unknown>;
const catalogues = import.meta.glob<Catalogue>('./locales/*/*.json', { import: 'default' });

/** Loads each language's namespace as its own chunk, only when asked for (D-39). */
const lazyCatalogues: BackendModule = {
  type: 'backend',
  init: () => undefined,
  read(language, namespace, callback) {
    const load = catalogues[`./locales/${language}/${namespace}.json`];
    if (load === undefined) {
      callback(new Error(`no ${namespace} catalogue for ${language}`), false);
      return;
    }
    load().then(
      (catalogue) => {
        callback(null, catalogue);
      },
      (error: unknown) => {
        callback(error instanceof Error ? error : new Error(String(error)), false);
      },
    );
  },
};

export async function createI18n(language: Language): Promise<I18n> {
  const instance = i18next.createInstance();
  await instance
    .use(lazyCatalogues)
    .use(initReactI18next)
    .init({
      lng: language,
      fallbackLng: DEFAULT_LANGUAGE,
      supportedLngs: LANGUAGES,
      ns: NAMESPACES,
      defaultNS: 'common',
      // React escapes what it renders.
      interpolation: { escapeValue: false },
    });
  return instance;
}

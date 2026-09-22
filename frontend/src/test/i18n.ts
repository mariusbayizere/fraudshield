import i18next, { type i18n as I18n, type Resource } from 'i18next';
import { initReactI18next } from 'react-i18next';
import { DEFAULT_LANGUAGE, NAMESPACES, type Language } from '../i18n/languages';

const catalogues = import.meta.glob<Record<string, unknown>>('../i18n/locales/*/*.json', {
  eager: true,
  import: 'default',
});

const resources: Resource = {};
for (const [path, catalogue] of Object.entries(catalogues)) {
  const [, language, namespace] = /locales\/(\w+)\/(\w+)\.json$/.exec(path) ?? [];
  if (language === undefined || namespace === undefined) continue;
  resources[language] = { ...resources[language], [namespace]: catalogue };
}

/** The real catalogues, loaded synchronously, for tests and stories. */
export function testI18n(language: Language = DEFAULT_LANGUAGE): I18n {
  const instance = i18next.createInstance();
  void instance.use(initReactI18next).init({
    lng: language,
    fallbackLng: DEFAULT_LANGUAGE,
    ns: NAMESPACES,
    defaultNS: 'common',
    resources,
    interpolation: { escapeValue: false },
    initAsync: false,
  });
  return instance;
}

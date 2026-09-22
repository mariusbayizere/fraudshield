import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import status from './translation-status.json' with { type: 'json' };
import { DEFAULT_LANGUAGE, LANGUAGES, NAMESPACES, type Language } from './languages';

const LOCALES = join(import.meta.dirname, 'locales');
const PLURAL = /_(zero|one|two|few|many|other)$/;

function flatten(value: unknown, prefix = ''): Map<string, string> {
  const out = new Map<string, string>();
  for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
    const path = `${prefix}${key}`;
    if (typeof child === 'string') out.set(path, child);
    else for (const [k, v] of flatten(child, `${path}.`)) out.set(k, v);
  }
  return out;
}

function catalogue(language: Language, namespace: string): Map<string, string> {
  return flatten(JSON.parse(readFileSync(join(LOCALES, language, `${namespace}.json`), 'utf8')));
}

/** Placeholder names, without i18next's format suffix ("{{block, number}}" is "block"). */
function placeholders(text: string): string[] {
  return [...text.matchAll(/\{\{\s*(\w+)[^}]*\}\}/g)].map((m) => m[1] ?? '').sort();
}

const bases = (keys: Iterable<string>) => new Set([...keys].map((k) => k.replace(PLURAL, '')));

describe('translation catalogues (D-43, ADR 0080 R5)', () => {
  it('has exactly the four languages and the declared namespaces', () => {
    expect(readdirSync(LOCALES).sort()).toEqual([...LANGUAGES].sort());
    for (const language of LANGUAGES) {
      expect(readdirSync(join(LOCALES, language)).sort()).toEqual(
        NAMESPACES.map((ns) => `${ns}.json`).sort(),
      );
    }
  });

  it("matches the contract's Locale enum", () => {
    const contract = readFileSync(
      join(import.meta.dirname, '../../../contracts/openapi/fraudshield-api.yaml'),
      'utf8',
    );
    const values = /\n {4}Locale:\n[\s\S]*?enum: \[([^\]]+)\]/.exec(contract)?.[1];
    expect(values?.split(',').map((v) => v.trim())).toEqual([...LANGUAGES]);
  });

  describe.each(NAMESPACES)('the %s namespace', (namespace) => {
    const source = catalogue(DEFAULT_LANGUAGE, namespace);

    it.each(LANGUAGES)('%s has every English string, and no others', (language) => {
      expect([...bases(catalogue(language, namespace).keys())].sort()).toEqual(
        [...bases(source.keys())].sort(),
      );
    });

    it.each(LANGUAGES)("%s has exactly its language's plural forms", (language) => {
      // ICU has no Kinyarwanda plural rules and falls back to English's one/other, which is
      // also what CLDR gives Kinyarwanda.
      const categories = new Intl.PluralRules(language).resolvedOptions().pluralCategories;
      const keys = [...catalogue(language, namespace).keys()];
      for (const base of new Set(
        keys.filter((k) => PLURAL.test(k)).map((k) => k.replace(PLURAL, '')),
      )) {
        const forms = keys.filter((k) => k.replace(PLURAL, '') === base && PLURAL.test(k));
        expect(forms.map((k) => PLURAL.exec(k)?.[1]).sort()).toEqual([...categories].sort());
      }
    });

    it.each(LANGUAGES)('%s keeps every placeholder', (language) => {
      const translated = catalogue(language, namespace);
      for (const [key, text] of translated) {
        const english = source.get(key) ?? source.get(key.replace(PLURAL, '_other')) ?? '';
        expect(placeholders(text), `${language} ${namespace}:${key}`).toEqual(
          placeholders(english),
        );
      }
    });

    it.each(LANGUAGES.filter((l) => l !== DEFAULT_LANGUAGE))(
      '%s gives every string a review status, and only real strings one',
      (language) => {
        const statuses =
          (status as Record<string, Record<string, Record<string, string>>>)[language]?.[
            namespace
          ] ?? {};
        expect(Object.keys(statuses).sort()).toEqual(
          [...catalogue(language, namespace).keys()].sort(),
        );
        for (const value of Object.values(statuses)) {
          expect(['machine_draft', 'reviewed']).toContain(value);
        }
      },
    );
  });
});

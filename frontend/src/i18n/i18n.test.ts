import { directionOverride } from './direction';
import { createI18n } from './i18n';
import { isLanguage, preferredLanguage } from './languages';

describe('createI18n', () => {
  it('loads a language lazily and translates with it', async () => {
    const i18n = await createI18n('fr');
    await i18n.loadNamespaces('designSystem');
    expect(i18n.t('tier.high', { ns: 'designSystem' })).toBe('RISQUE ÉLEVÉ');
    expect(i18n.dir()).toBe('ltr');
  });

  it('falls back to English for a string a language lacks', async () => {
    const i18n = await createI18n('sw');
    expect(i18n.t('app.name')).toBe('FraudShield');
  });

  it('reports a namespace that has no catalogue instead of hanging', async () => {
    const i18n = await createI18n('en');
    await i18n.loadNamespaces('nonexistent');
    expect(i18n.hasResourceBundle('en', 'nonexistent')).toBe(false);
  });
});

describe('language and direction choice', () => {
  it.each([
    [['fr-CD', 'en'], 'fr'],
    [['sw-TZ'], 'sw'],
    [['RW'], 'rw'],
    [['de-DE', 'en-GB'], 'en'],
    [['de'], 'en'],
    [[], 'en'],
  ])('picks %j as %s', (browser, language) => {
    expect(preferredLanguage(browser)).toBe(language);
  });

  it('knows its own languages only', () => {
    expect(isLanguage('rw')).toBe(true);
    expect(isLanguage('ar')).toBe(false);
    expect(isLanguage(3)).toBe(false);
  });

  it.each([
    ['?dir=rtl', 'rtl'],
    ['?dir=ltr', 'ltr'],
    ['?dir=RTL', undefined],
    ['?lang=fr', undefined],
    ['', undefined],
  ])('reads %j as %s', (search, direction) => {
    expect(directionOverride(search)).toBe(direction);
  });
});

import { rememberedLanguage, rememberLanguage } from './preferences';

describe('the remembered language', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('round-trips a supported language and ignores anything else', () => {
    const store = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => store.set(k, v),
    });
    expect(rememberedLanguage()).toBeUndefined();
    rememberLanguage('sw');
    expect(rememberedLanguage()).toBe('sw');
    store.set('fs.language', 'xx');
    expect(rememberedLanguage()).toBeUndefined();
  });

  it('works without storage, as in a private window', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    });
    expect(() => {
      rememberLanguage('fr');
    }).not.toThrow();
    expect(rememberedLanguage()).toBeUndefined();
  });
});

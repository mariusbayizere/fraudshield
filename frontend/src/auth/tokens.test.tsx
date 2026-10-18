import { getCurrentUser } from '../api/generated/sdk.gen';
import { getAccessToken, installAuthInterceptor, setAccessToken } from './tokens';

afterEach(() => {
  setAccessToken(null);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('the access token (D-27, ADR 0014)', () => {
  it('never reaches browser storage', () => {
    const local = vi.spyOn(Storage.prototype, 'setItem');
    setAccessToken('secret-token');
    expect(getAccessToken()).toBe('secret-token');
    expect(local).not.toHaveBeenCalled();
    expect(localStorage.getItem('fs.token')).toBeNull();
    expect(sessionStorage.getItem('fs.token')).toBeNull();
    const dump = (store: Storage) =>
      Object.keys(store)
        .map((key) => store.getItem(key) ?? '')
        .join('|');
    expect(dump(localStorage) + dump(sessionStorage)).not.toContain('secret-token');
    expect(document.cookie).not.toContain('secret-token');
  });

  it('goes out with every API call, and stops when the session ends', async () => {
    installAuthInterceptor();
    const sent: (string | null)[] = [];
    vi.stubGlobal('fetch', (request: Request) => {
      sent.push(request.headers.get('Authorization'));
      return Promise.resolve(
        new Response(JSON.stringify({}), {
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    });
    setAccessToken('secret-token');
    await getCurrentUser();
    setAccessToken(null);
    await getCurrentUser();
    expect(sent).toEqual(['Bearer secret-token', null]);
  });
});

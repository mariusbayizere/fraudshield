import { client } from '../api/generated/client.gen';

/**
 * The access token lives in memory for the tab's lifetime and nowhere else.
 *
 * Not localStorage or sessionStorage: anything a script can read, an injected script can steal,
 * and these consoles run on shared machines (D-29's reasoning about cached data applies here
 * too). The refresh token is an httpOnly cookie the browser holds and this code never sees.
 */
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

let installed = false;

/** Sends the access token with every API request. Installed once, at start-up. */
export function installAuthInterceptor(): void {
  if (installed) return;
  installed = true;
  client.interceptors.request.use((request) => {
    if (accessToken !== null) {
      request.headers.set('Authorization', `Bearer ${accessToken}`);
    }
    return request;
  });
}

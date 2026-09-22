/**
 * The double-submit CSRF token (D-27): the server sets a readable `fs_csrf` cookie and expects
 * the same value in `X-CSRF-Token` on refresh and logout, the two calls that work from the
 * cookie alone.
 *
 * The contract names the header and the refresh cookie but not this cookie; `fs_csrf` is the
 * name M7's server sets. See docs/parallel/M8_updates.md.
 */
export const CSRF_COOKIE = 'fs_csrf';

export function csrfToken(
  cookies = typeof document === 'undefined' ? '' : document.cookie,
): string {
  for (const part of cookies.split(';')) {
    const [name, ...rest] = part.trim().split('=');
    if (name === CSRF_COOKIE) return decodeURIComponent(rest.join('='));
  }
  return '';
}

/** Headers for the calls that authenticate with the refresh cookie. */
export function csrfHeaders(): { headers: { 'X-CSRF-Token': string } } {
  return { headers: { 'X-CSRF-Token': csrfToken() } };
}

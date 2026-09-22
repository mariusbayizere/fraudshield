import { csrfToken } from './csrf';

describe('the double-submit CSRF token (D-27)', () => {
  it.each([
    ['fs_csrf=abc123', 'abc123'],
    ['other=1; fs_csrf=abc123; more=2', 'abc123'],
    ['  fs_csrf = spaced ', ''],
    ['fs_csrf=a%2Fb%3Dc', 'a/b=c'],
    ['fs_csrf=has=equals', 'has=equals'],
    ['fs_csrfx=nope', ''],
    ['', ''],
  ])('reads %j as %j', (cookies, token) => {
    expect(csrfToken(cookies)).toBe(token);
  });
});

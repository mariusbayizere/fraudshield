import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { bodyOf } from '../api/body';
import { logout as logoutRequest, refreshToken } from '../api/generated/sdk.gen';
import type { StaffUser, TokenResponse } from '../api/generated/types.gen';
import { csrfHeaders } from './csrf';
import { installAuthInterceptor, setAccessToken } from './tokens';

export type SessionStatus = 'restoring' | 'authenticated' | 'anonymous';

export interface Session {
  status: SessionStatus;
  user: StaffUser | null;
  signIn: (token: TokenResponse) => void;
  signOut: () => Promise<void>;
}

const SessionContext = createContext<Session | null>(null);

/**
 * The signed-in staff member, restored from the refresh cookie at start-up.
 *
 * A reload keeps the session without storing a token where a script could read it: the browser
 * sends the httpOnly refresh cookie, the server answers with a fresh access token, and it stays
 * in memory (D-27's rotation applies on every refresh).
 */
export function SessionProvider({
  children,
  initial = 'restoring',
}: {
  children: ReactNode;
  initial?: SessionStatus;
}) {
  const [status, setStatus] = useState<SessionStatus>(initial);
  const [user, setUser] = useState<StaffUser | null>(null);

  const signIn = useCallback((token: TokenResponse) => {
    setAccessToken(token.access_token);
    setUser(token.user);
    setStatus('authenticated');
  }, []);

  const signOut = useCallback(async () => {
    try {
      await logoutRequest(csrfHeaders());
    } finally {
      setAccessToken(null);
      setUser(null);
      setStatus('anonymous');
    }
  }, []);

  useEffect(() => {
    if (initial !== 'restoring') return undefined;
    installAuthInterceptor();
    const live = { current: true };
    void (async () => {
      const session = bodyOf(await refreshToken(csrfHeaders()));
      if (!live.current) return;
      if (session === undefined) {
        setStatus('anonymous');
        return;
      }
      signIn(session);
    })();
    return () => {
      live.current = false;
    };
  }, [initial, signIn]);

  const value = useMemo<Session>(
    () => ({ status, user, signIn, signOut }),
    [status, user, signIn, signOut],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Session {
  const session = useContext(SessionContext);
  if (session === null) throw new Error('useSession() needs a SessionProvider above it');
  return session;
}

import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
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
  initialUser = null,
}: {
  children: ReactNode;
  initial?: SessionStatus;
  /** The signed-in staff member when `initial` is 'authenticated' (tests and stories). */
  initialUser?: StaffUser | null;
}) {
  const [status, setStatus] = useState<SessionStatus>(initial);
  const [user, setUser] = useState<StaffUser | null>(initialUser);
  const queryClient = useQueryClient();

  // Who the cache was loaded for, read inside signIn without making it change identity.
  const loadedFor = useRef<string | null>(initialUser?.user_id ?? null);

  // Someone else signing in over a session that never signed out (one that expired, say) must
  // not see what it loaded either. Only the queries go: the sign-in itself is a mutation in flight.
  const signIn = useCallback(
    (token: TokenResponse) => {
      if (loadedFor.current !== null && loadedFor.current !== token.user.user_id) {
        queryClient.removeQueries();
      }
      loadedFor.current = token.user.user_id;
      setAccessToken(token.access_token);
      setUser(token.user);
      setStatus('authenticated');
    },
    [queryClient],
  );

  // Everything the session loaded goes with it: the console runs on shared machines, and the
  // query cache would otherwise show the last staff member's alerts to the next one to sign in on
  // the same tab (PB-140). clear() drops the queries and the mutations, comments included; the
  // screens that held other state sit behind RequireSession and unmount with the session.
  const signOut = useCallback(async () => {
    try {
      await logoutRequest(csrfHeaders());
    } finally {
      setAccessToken(null);
      setUser(null);
      setStatus('anonymous');
      loadedFor.current = null;
      void queryClient.cancelQueries();
      queryClient.clear();
    }
  }, [queryClient]);

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

import CircularProgress from '@mui/material/CircularProgress';
import Box from '@mui/material/Box';
import { useNavigate, useRouterState } from '@tanstack/react-router';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useSession } from './session';

/**
 * Keeps the console behind a session. While the refresh cookie is being tried the screen shows a
 * busy indicator rather than a flash of the login page; an anonymous visitor is sent to /login
 * with the page they asked for, so signing in returns them to it.
 */
export function RequireSession({ children }: { children: ReactNode }) {
  const { status } = useSession();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const href = useRouterState({ select: (state) => state.location.href });
  // The page asked for, captured once: after the redirect the location is /login, and sending
  // the analyst back there after signing in would be a loop.
  const [asked] = useState(href);

  useEffect(() => {
    if (status === 'anonymous') {
      void navigate({ to: '/login', search: { next: asked }, replace: true });
    }
  }, [status, navigate, asked]);

  if (status === 'authenticated') return children;
  return (
    <Box
      sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100dvh' }}
    >
      <CircularProgress aria-label={t('shell.restoringSession')} />
    </Box>
  );
}

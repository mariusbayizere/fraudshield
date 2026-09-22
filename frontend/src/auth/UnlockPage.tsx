import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink, useRouterState } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';
import { unlockAccount } from '../api/generated/sdk.gen';
import { tokens } from '../design-system/tokens';
import { unlockSearch } from './unlockSearch';

/**
 * Unlocking an account from the emailed link (FR-07-05, D-26). The token is used once; an
 * expired or already-used link says so rather than failing silently.
 */
export default function UnlockPage() {
  const { t } = useTranslation('auth');
  const search = useRouterState({ select: (state): unknown => state.location.search });
  const { token } = unlockSearch.parse(search);

  const unlock = useQuery({
    queryKey: ['unlock', token],
    enabled: token !== undefined,
    retry: false,
    queryFn: async () => {
      const { response } = await unlockAccount({ body: { token: token ?? '' } });
      if (response?.status === 204) return 'unlocked' as const;
      throw new Error(response?.status === 410 ? 'expired' : 'refused');
    },
  });

  const state =
    token === undefined
      ? 'missing'
      : unlock.isPending
        ? 'working'
        : unlock.isSuccess
          ? 'unlocked'
          : unlock.error.message === 'expired'
            ? 'expired'
            : 'refused';

  return (
    <Box component="main" sx={{ display: 'flex', justifyContent: 'center', p: 2 }}>
      <Paper variant="outlined" sx={{ p: 3, mt: 6, width: '100%', maxWidth: 480 }}>
        <Typography variant="h5" component="h1" gutterBottom>
          {t('unlock.title')}
        </Typography>
        <Stack spacing={2}>
          {state === 'working' ? (
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <CircularProgress size={20} aria-hidden />
              <Typography>{t('unlock.working')}</Typography>
            </Stack>
          ) : (
            <Alert severity={state === 'unlocked' ? 'success' : 'error'}>
              {t(`unlock.${state}`)}
            </Alert>
          )}
          {state === 'expired' || state === 'refused' || state === 'missing' ? (
            <Button
              component={RouterLink}
              to="/forgot-password"
              variant="outlined"
              sx={{ minHeight: tokens.touchTargetPx }}
            >
              {t('unlock.resetInstead')}
            </Button>
          ) : null}
          <Link component={RouterLink} to="/login">
            {t('forgot.backToSignIn')}
          </Link>
        </Stack>
      </Paper>
    </Box>
  );
}

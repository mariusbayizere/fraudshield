import Visibility from '@mui/icons-material/Visibility';
import VisibilityOff from '@mui/icons-material/VisibilityOff';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate, useRouterState } from '@tanstack/react-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { bodyOf } from '../api/body';
import { login } from '../api/generated/sdk.gen';
import { tokens } from '../design-system/tokens';
import { loginSearch } from './loginSearch';
import { useSession } from './session';

/** What the console says for each answer the contract allows (FR-07-03, ADR 0014). */
export type LoginFailure = 'invalidCredentials' | 'locked' | 'tooManyAttempts' | 'unavailable';

export class SignInError extends Error {
  constructor(readonly failure: LoginFailure) {
    super(failure);
    this.name = 'SignInError';
  }
}

export function failureFor(status: number | undefined): LoginFailure {
  if (status === 401 || status === 400 || status === 422) return 'invalidCredentials';
  if (status === 423) return 'locked';
  if (status === 429) return 'tooManyAttempts';
  return 'unavailable';
}

export default function LoginPage() {
  const { t } = useTranslation('auth');
  const { signIn } = useSession();
  const navigate = useNavigate();
  // The route tree is built from arrays, so the router cannot type this route's search; the
  // schema that validates it is the source of the type either way.
  const search = useRouterState({
    select: (state): unknown => state.location.search,
  });
  const { next } = loginSearch.parse(search);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [visible, setVisible] = useState(false);

  const signInMutation = useMutation<'done', SignInError>({
    mutationFn: async (): Promise<'done'> => {
      const result = await login({ body: { email, password } });
      const { response } = result;
      const session = bodyOf(result);
      if (session === undefined) {
        // 403 means the credentials were right but the account cannot sign in yet (D-23).
        if (response?.status === 403) {
          await navigate({ to: '/pending-approval', replace: true });
          return 'done';
        }
        throw new SignInError(failureFor(response?.status));
      }
      signIn(session);
      await navigate({ to: next ?? '/alerts', replace: true });
      return 'done';
    },
  });

  const onSubmit = (event: { preventDefault: () => void }) => {
    event.preventDefault();
    signInMutation.mutate();
  };

  return (
    <Box
      component="main"
      sx={{ display: 'flex', justifyContent: 'center', p: 2, minHeight: '100dvh' }}
    >
      <Paper
        variant="outlined"
        sx={{ p: 3, mt: 6, width: '100%', maxWidth: 420, height: 'fit-content' }}
      >
        <Typography variant="h5" component="h1" gutterBottom>
          {t('login.title')}
        </Typography>
        <Box component="form" onSubmit={onSubmit} noValidate>
          <Stack spacing={2}>
            {signInMutation.error === null ? null : (
              <Alert severity="error">{t(`login.error.${signInMutation.error.failure}`)}</Alert>
            )}
            <TextField
              label={t('login.email')}
              type="email"
              value={email}
              onChange={(event) => {
                setEmail(event.target.value);
              }}
              autoComplete="username"
              required
              fullWidth
            />
            <TextField
              label={t('login.password')}
              type={visible ? 'text' : 'password'}
              value={password}
              onChange={(event) => {
                setPassword(event.target.value);
              }}
              autoComplete="current-password"
              required
              fullWidth
              slotProps={{
                input: {
                  endAdornment: (
                    <InputAdornment position="end">
                      <IconButton
                        onClick={() => {
                          setVisible((shown) => !shown);
                        }}
                        aria-label={t(visible ? 'login.hidePassword' : 'login.showPassword')}
                        aria-pressed={visible}
                        edge="end"
                        sx={{ minWidth: tokens.touchTargetPx, minHeight: tokens.touchTargetPx }}
                      >
                        {visible ? <VisibilityOff aria-hidden /> : <Visibility aria-hidden />}
                      </IconButton>
                    </InputAdornment>
                  ),
                },
              }}
            />
            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={signInMutation.isPending}
              startIcon={
                signInMutation.isPending ? <CircularProgress size={16} color="inherit" /> : null
              }
              sx={{ minHeight: tokens.touchTargetPx }}
            >
              {t(signInMutation.isPending ? 'login.signingIn' : 'login.signIn')}
            </Button>
            <Stack direction="row" spacing={2} sx={{ justifyContent: 'space-between' }}>
              <Link component={RouterLink} to="/forgot-password">
                {t('login.forgotPassword')}
              </Link>
              <Link component={RouterLink} to="/register">
                {t('login.register')}
              </Link>
            </Stack>
          </Stack>
        </Box>
      </Paper>
    </Box>
  );
}

import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation } from '@tanstack/react-query';
import { Link as RouterLink } from '@tanstack/react-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { requestPasswordReset } from '../api/generated/sdk.gen';
import { tokens } from '../design-system/tokens';
import { emailProblem } from './registration/fields';

/**
 * Asks for a reset code (FR-07-06). The answer never says whether the email is registered: that
 * would let anyone test addresses. It is the same sentence either way, as the server's 202 is.
 */
export default function ForgotPasswordPage() {
  const { t } = useTranslation('auth');
  const [email, setEmail] = useState('');
  const [touched, setTouched] = useState(false);
  const problem = emailProblem(email);

  const ask = useMutation<'asked'>({
    mutationFn: async (): Promise<'asked'> => {
      const { response } = await requestPasswordReset({ body: { email: email.trim() } });
      if (response?.status === 429) throw new Error('too many attempts');
      return 'asked';
    },
  });

  return (
    <Box component="main" sx={{ display: 'flex', justifyContent: 'center', p: 2 }}>
      <Paper variant="outlined" sx={{ p: 3, mt: 6, width: '100%', maxWidth: 420 }}>
        <Typography variant="h5" component="h1" gutterBottom>
          {t('forgot.title')}
        </Typography>
        {ask.isSuccess ? (
          <Stack spacing={2}>
            <Alert severity="success">{t('forgot.sent')}</Alert>
            <Link component={RouterLink} to="/reset-password">
              {t('forgot.haveCode')}
            </Link>
          </Stack>
        ) : (
          <Box
            component="form"
            noValidate
            onSubmit={(event: { preventDefault: () => void }) => {
              event.preventDefault();
              setTouched(true);
              if (problem === undefined) ask.mutate();
            }}
          >
            <Stack spacing={2}>
              <Typography variant="body2" color="text.secondary">
                {t('forgot.explanation')}
              </Typography>
              {ask.isError ? <Alert severity="error">{t('forgot.tooMany')}</Alert> : null}
              <TextField
                label={t('login.email')}
                type="email"
                value={email}
                onChange={(event) => {
                  setEmail(event.target.value);
                }}
                onBlur={() => {
                  setTouched(true);
                }}
                error={touched && problem !== undefined}
                helperText={
                  touched && problem !== undefined ? t(`register.problem.${problem}`) : ' '
                }
                autoComplete="username"
                required
                fullWidth
              />
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={ask.isPending}
                startIcon={ask.isPending ? <CircularProgress size={16} color="inherit" /> : null}
                sx={{ minHeight: tokens.touchTargetPx }}
              >
                {t(ask.isPending ? 'forgot.sending' : 'forgot.submit')}
              </Button>
              <Link component={RouterLink} to="/login">
                {t('forgot.backToSignIn')}
              </Link>
            </Stack>
          </Box>
        )}
      </Paper>
    </Box>
  );
}

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
import { Link as RouterLink, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { bodyOf } from '../api/body';
import { completePasswordReset, verifyPasswordResetCode } from '../api/generated/sdk.gen';
import { tokens } from '../design-system/tokens';
import { PasswordStrength } from './registration/PasswordStrength';
import { confirmationProblem, emailProblem, passwordFieldProblem } from './registration/fields';

const CODE = /^[0-9]{6}$/;

/**
 * Completing a password reset (FR-07-06): the six-digit code is exchanged for a single-use
 * token, and only then is a new password accepted. A code that has expired says so plainly.
 */
export default function ResetPasswordPage() {
  const { t } = useTranslation('auth');
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [resetToken, setResetToken] = useState<string | null>(null);

  const verify = useMutation<'verified'>({
    mutationFn: async (): Promise<'verified'> => {
      const accepted = bodyOf(
        await verifyPasswordResetCode({ body: { email: email.trim(), code } }),
      );
      if (accepted === undefined) throw new Error('the code was not accepted');
      setResetToken(accepted.reset_token);
      return 'verified';
    },
  });

  const complete = useMutation<'reset', Error, string>({
    mutationFn: async (token: string): Promise<'reset'> => {
      const { response } = await completePasswordReset({
        body: { reset_token: token, new_password: password },
      });
      if (response?.status !== 204) {
        throw new Error(response?.status === 410 ? 'expired' : 'refused');
      }
      await navigate({ to: '/login', replace: true });
      return 'reset';
    },
  });

  const codeStep = resetToken === null;
  const canVerify = emailProblem(email) === undefined && CODE.test(code);
  const passwordProblem = passwordFieldProblem(password);
  const canComplete =
    passwordProblem === undefined && confirmationProblem(password, confirmation) === undefined;

  return (
    <Box component="main" sx={{ display: 'flex', justifyContent: 'center', p: 2 }}>
      <Paper variant="outlined" sx={{ p: 3, mt: 6, width: '100%', maxWidth: 480 }}>
        <Typography variant="h5" component="h1" gutterBottom>
          {t('reset.title')}
        </Typography>
        {codeStep ? (
          <Box
            component="form"
            noValidate
            onSubmit={(event: { preventDefault: () => void }) => {
              event.preventDefault();
              if (canVerify) verify.mutate();
            }}
          >
            <Stack spacing={2}>
              <Typography variant="body2" color="text.secondary">
                {t('reset.codeExplanation')}
              </Typography>
              {verify.isError ? <Alert severity="error">{t('reset.codeRefused')}</Alert> : null}
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
                label={t('reset.code')}
                value={code}
                onChange={(event) => {
                  setCode(event.target.value.replace(/\D/g, '').slice(0, 6));
                }}
                inputMode="numeric"
                autoComplete="one-time-code"
                required
                fullWidth
              />
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={verify.isPending || !canVerify}
                startIcon={verify.isPending ? <CircularProgress size={16} color="inherit" /> : null}
                sx={{ minHeight: tokens.touchTargetPx }}
              >
                {t('reset.checkCode')}
              </Button>
              <Link component={RouterLink} to="/forgot-password">
                {t('reset.needCode')}
              </Link>
            </Stack>
          </Box>
        ) : (
          <Box
            component="form"
            noValidate
            onSubmit={(event: { preventDefault: () => void }) => {
              event.preventDefault();
              if (canComplete) complete.mutate(resetToken);
            }}
          >
            <Stack spacing={2}>
              {complete.isError ? (
                <Alert severity="error">
                  {t(complete.error.message === 'expired' ? 'reset.expired' : 'reset.refused')}
                </Alert>
              ) : null}
              <TextField
                label={t('reset.newPassword')}
                type="password"
                value={password}
                onChange={(event) => {
                  setPassword(event.target.value);
                }}
                autoComplete="new-password"
                required
                fullWidth
              />
              <PasswordStrength password={password} />
              <TextField
                label={t('register.confirmation')}
                type="password"
                value={confirmation}
                onChange={(event) => {
                  setConfirmation(event.target.value);
                }}
                autoComplete="new-password"
                required
                fullWidth
              />
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={complete.isPending || !canComplete}
                startIcon={
                  complete.isPending ? <CircularProgress size={16} color="inherit" /> : null
                }
                sx={{ minHeight: tokens.touchTargetPx }}
              >
                {t('reset.setPassword')}
              </Button>
            </Stack>
          </Box>
        )}
      </Paper>
    </Box>
  );
}

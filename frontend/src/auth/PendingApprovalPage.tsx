import Box from '@mui/material/Box';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link as RouterLink } from '@tanstack/react-router';
import { useTranslation } from 'react-i18next';

/**
 * Where an account that exists but cannot sign in yet lands (D-23, D-24): registered, or created
 * by Google sign-in on an allow-listed domain, and waiting for an administrator.
 */
export default function PendingApprovalPage() {
  const { t } = useTranslation('auth');
  return (
    <Box component="main" sx={{ display: 'flex', justifyContent: 'center', p: 2 }}>
      <Paper variant="outlined" sx={{ p: 3, mt: 6, width: '100%', maxWidth: 480 }}>
        <Stack spacing={2}>
          <Typography variant="h5" component="h1">
            {t('pending.title')}
          </Typography>
          <Typography>{t('pending.explanation')}</Typography>
          <Typography variant="body2" color="text.secondary">
            {t('pending.nextStep')}
          </Typography>
          <Link component={RouterLink} to="/login">
            {t('forgot.backToSignIn')}
          </Link>
        </Stack>
      </Paper>
    </Box>
  );
}

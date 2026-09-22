import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Divider from '@mui/material/Divider';
import Grid from '@mui/material/Grid';
import InputAdornment from '@mui/material/InputAdornment';
import Link from '@mui/material/Link';
import MenuItem from '@mui/material/MenuItem';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useMutation } from '@tanstack/react-query';
import { Link as RouterLink, useNavigate } from '@tanstack/react-router';
import type { CountryCode } from 'libphonenumber-js/min';
import { useDeferredValue, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { bodyOf } from '../../api/body';
import { register } from '../../api/generated/sdk.gen';
import type { RegistrationRequest } from '../../api/generated/types.gen';
import { tokens } from '../../design-system/tokens';
import { isLanguage } from '../../i18n/languages';
import { useRegion } from '../../region/RegionContext';
import { CountrySelect } from './CountrySelect';
import { PasswordStrength } from './PasswordStrength';
import {
  confirmationProblem,
  defaultDialCountry,
  emailProblem,
  employeeIdProblem,
  nameProblem,
  passwordFieldProblem,
  toE164,
  type FieldProblem,
} from './fields';
import { useAvailability } from './useAvailability';

/** The departments and roles the contract allows (D-24 keeps them separate). */
const DEPARTMENTS: RegistrationRequest['department'][] = [
  'FRAUD_OPERATIONS',
  'RISK',
  'COMPLIANCE',
  'IT',
  'OTHER',
];
const ROLES: RegistrationRequest['requested_role'][] = [
  'ANALYST',
  'SENIOR_ANALYST',
  'RISK_OFFICER',
  'ADMIN',
];

interface Fields {
  firstName: string;
  lastName: string;
  email: string;
  country: CountryCode | '';
  localNumber: string;
  employeeId: string;
  department: RegistrationRequest['department'];
  requestedRole: RegistrationRequest['requested_role'];
  password: string;
  confirmation: string;
}

export default function RegisterPage() {
  const { t, i18n } = useTranslation('auth');
  const navigate = useNavigate();
  const { region } = useRegion();
  const language = i18n.resolvedLanguage ?? 'en';
  const [touched, setTouched] = useState<Partial<Record<keyof Fields, true>>>({});
  const [fields, setFields] = useState<Fields>({
    firstName: '',
    lastName: '',
    email: '',
    // The deployment's own country, when it is one the phone metadata knows (ADR 0023).
    country: defaultDialCountry(region.country),
    localNumber: '',
    employeeId: '',
    department: 'FRAUD_OPERATIONS',
    requestedRole: 'ANALYST',
    password: '',
    confirmation: '',
  });

  const set = <K extends keyof Fields>(key: K, value: Fields[K]) => {
    setFields((current) => ({ ...current, [key]: value }));
  };
  const blur = (key: keyof Fields) => () => {
    setTouched((current) => ({ ...current, [key]: true }));
  };

  const phone = fields.country === '' ? undefined : toE164(fields.country, fields.localNumber);
  const problems: { [K in keyof Fields]?: FieldProblem | undefined } = {
    firstName: nameProblem(fields.firstName),
    lastName: nameProblem(fields.lastName),
    email: emailProblem(fields.email),
    employeeId: employeeIdProblem(fields.employeeId),
    country: fields.country === '' ? 'country' : undefined,
    localNumber:
      fields.localNumber === ''
        ? 'required'
        : fields.country === ''
          ? 'country'
          : phone === undefined
            ? 'phone'
            : undefined,
    password: passwordFieldProblem(fields.password),
    confirmation: confirmationProblem(fields.password, fields.confirmation),
  };

  // The availability checks run on the settled value, not on every keystroke (SRS 5.3).
  const settledEmail = useDeferredValue(fields.email);
  const settledEmployeeId = useDeferredValue(fields.employeeId);
  const emailAvailability = useAvailability(
    'email',
    settledEmail,
    problems.email === undefined && touched.email === true,
  );
  const employeeIdAvailability = useAvailability(
    'employee_id',
    settledEmployeeId,
    problems.employeeId === undefined && touched.employeeId === true,
  );

  const complete =
    Object.values(problems).every((problem) => problem === undefined) &&
    emailAvailability !== 'taken' &&
    employeeIdAvailability !== 'taken';

  const submit = useMutation<'sent'>({
    mutationFn: async (): Promise<'sent'> => {
      const body: RegistrationRequest = {
        first_name: fields.firstName.trim(),
        last_name: fields.lastName.trim(),
        email: fields.email.trim(),
        phone: phone ?? '',
        employee_id: fields.employeeId,
        department: fields.department,
        requested_role: fields.requestedRole,
        password: fields.password,
        preferred_locale: isLanguage(language) ? language : 'en',
      };
      const accepted = bodyOf(await register({ body }));
      if (accepted === undefined) throw new Error('registration was not accepted');
      await navigate({ to: '/pending-approval', replace: true });
      return 'sent';
    },
  });

  const helper = (key: keyof Fields) =>
    touched[key] === true && problems[key] !== undefined
      ? t(`register.problem.${problems[key]}`)
      : ' ';
  const invalid = (key: keyof Fields) => touched[key] === true && problems[key] !== undefined;

  return (
    <Box component="main" sx={{ display: 'flex', justifyContent: 'center', p: 2 }}>
      <Paper variant="outlined" sx={{ p: 3, my: 4, width: '100%', maxWidth: 720 }}>
        <Typography variant="h5" component="h1" gutterBottom>
          {t('register.title')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('register.approvalNotice')}
        </Typography>
        <Box
          component="form"
          noValidate
          onSubmit={(event: { preventDefault: () => void }) => {
            event.preventDefault();
            setTouched({
              country: true,
              firstName: true,
              lastName: true,
              email: true,
              localNumber: true,
              employeeId: true,
              password: true,
              confirmation: true,
            });
            if (complete) submit.mutate();
          }}
        >
          <Grid container spacing={2}>
            {submit.isError ? (
              <Grid size={12}>
                <Alert severity="error">{t('register.failed')}</Alert>
              </Grid>
            ) : null}
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField
                label={t('register.firstName')}
                value={fields.firstName}
                onChange={(event) => {
                  set('firstName', event.target.value);
                }}
                onBlur={blur('firstName')}
                error={invalid('firstName')}
                helperText={helper('firstName')}
                autoComplete="given-name"
                required
                fullWidth
              />
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField
                label={t('register.lastName')}
                value={fields.lastName}
                onChange={(event) => {
                  set('lastName', event.target.value);
                }}
                onBlur={blur('lastName')}
                error={invalid('lastName')}
                helperText={helper('lastName')}
                autoComplete="family-name"
                required
                fullWidth
              />
            </Grid>
            <Grid size={12}>
              <TextField
                label={t('register.email')}
                type="email"
                value={fields.email}
                onChange={(event) => {
                  set('email', event.target.value);
                }}
                onBlur={blur('email')}
                error={invalid('email') || emailAvailability === 'taken'}
                helperText={
                  emailAvailability === 'taken'
                    ? t('register.problem.emailTaken')
                    : emailAvailability === 'checking'
                      ? t('register.checking')
                      : helper('email')
                }
                autoComplete="email"
                required
                fullWidth
              />
            </Grid>
            <Grid size={{ xs: 12, md: 5 }}>
              <CountrySelect
                label={t('register.country')}
                value={fields.country}
                language={language}
                placeholder={t('register.chooseCountry')}
                onChange={(country) => {
                  set('country', country);
                  setTouched((current) => ({ ...current, country: true }));
                }}
              />
            </Grid>
            <Grid size={{ xs: 12, md: 7 }}>
              <TextField
                label={t('register.phone')}
                value={fields.localNumber}
                onChange={(event) => {
                  set('localNumber', event.target.value);
                }}
                onBlur={blur('localNumber')}
                error={invalid('localNumber')}
                helperText={invalid('localNumber') ? helper('localNumber') : (phone ?? ' ')}
                autoComplete="tel-national"
                inputMode="tel"
                required
                fullWidth
              />
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField
                label={t('register.employeeId')}
                value={fields.employeeId}
                onChange={(event) => {
                  set('employeeId', event.target.value);
                }}
                onBlur={blur('employeeId')}
                error={invalid('employeeId') || employeeIdAvailability === 'taken'}
                helperText={
                  employeeIdAvailability === 'taken'
                    ? t('register.problem.employeeIdTaken')
                    : employeeIdAvailability === 'checking'
                      ? t('register.checking')
                      : helper('employeeId')
                }
                required
                fullWidth
              />
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField
                select
                label={t('register.department')}
                value={fields.department}
                onChange={(event) => {
                  set('department', event.target.value as RegistrationRequest['department']);
                }}
                fullWidth
              >
                {DEPARTMENTS.map((department) => (
                  <MenuItem key={department} value={department}>
                    {t(`register.departments.${department}`)}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={12}>
              <TextField
                select
                label={t('register.requestedRole')}
                value={fields.requestedRole}
                onChange={(event) => {
                  set('requestedRole', event.target.value as RegistrationRequest['requested_role']);
                }}
                helperText={t('register.roleNotice')}
                fullWidth
              >
                {ROLES.map((role) => (
                  <MenuItem key={role} value={role}>
                    {t(`register.roles.${role}`)}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField
                label={t('register.passwordLabel')}
                type="password"
                value={fields.password}
                onChange={(event) => {
                  set('password', event.target.value);
                }}
                onBlur={blur('password')}
                error={invalid('password')}
                helperText={helper('password')}
                autoComplete="new-password"
                required
                fullWidth
              />
              <PasswordStrength password={fields.password} />
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField
                label={t('register.confirmation')}
                type="password"
                value={fields.confirmation}
                onChange={(event) => {
                  set('confirmation', event.target.value);
                  setTouched((current) => ({ ...current, confirmation: true }));
                }}
                onBlur={blur('confirmation')}
                error={invalid('confirmation')}
                helperText={helper('confirmation')}
                autoComplete="new-password"
                required
                fullWidth
                slotProps={{
                  input: {
                    endAdornment:
                      fields.confirmation !== '' && problems.confirmation === undefined ? (
                        <InputAdornment position="end">✓</InputAdornment>
                      ) : null,
                  },
                }}
              />
            </Grid>
            <Grid size={12}>
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={submit.isPending}
                startIcon={submit.isPending ? <CircularProgress size={16} color="inherit" /> : null}
                sx={{ minHeight: tokens.touchTargetPx, width: { xs: '100%', md: 'auto' } }}
              >
                {t(submit.isPending ? 'register.submitting' : 'register.submit')}
              </Button>
            </Grid>
          </Grid>
        </Box>
        <Divider sx={{ my: 3 }} />
        <Stack direction="row" spacing={1}>
          <Typography variant="body2">{t('register.haveAccount')}</Typography>
          <Link component={RouterLink} to="/login">
            {t('register.signIn')}
          </Link>
        </Stack>
      </Paper>
    </Box>
  );
}

import CheckCircleOutlined from '@mui/icons-material/CheckCircleOutlined';
import RadioButtonUnchecked from '@mui/icons-material/RadioButtonUnchecked';
import Box from '@mui/material/Box';
import LinearProgress from '@mui/material/LinearProgress';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Typography from '@mui/material/Typography';
import { useTranslation } from 'react-i18next';
import { PASSWORD_FAILURES, passwordRules, passwordStrength } from './password';

const TIER = ['low', 'low', 'medium', 'medium', 'high'] as const;
const LEVEL_KEY = [
  'register.password.level.0',
  'register.password.level.1',
  'register.password.level.2',
  'register.password.level.3',
  'register.password.level.4',
] as const;

/**
 * The four-level strength meter with the rules still unmet listed below it (SRS 5.3). The level
 * is written as words as well as colour and length, so it does not depend on sight of the bar.
 */
export function PasswordStrength({ password }: { password: string }) {
  const { t } = useTranslation('auth');
  const level = passwordStrength(password);
  const met = passwordRules(password);
  const unmet = PASSWORD_FAILURES.filter((rule) => !met[rule]);
  return (
    <Box>
      <LinearProgress
        variant="determinate"
        value={level * 25}
        aria-hidden
        sx={(theme) => ({
          height: 6,
          borderRadius: 3,
          '& .MuiLinearProgress-bar': {
            backgroundColor:
              level === 0 ? theme.palette.divider : theme.palette.risk[TIER[level]].border,
          },
        })}
      />
      <Typography variant="body2" sx={{ mt: 0.5 }}>
        {t('register.password.strength', { level: t(LEVEL_KEY[level]) })}
      </Typography>
      {unmet.length === 0 ? null : (
        <List dense disablePadding aria-label={t('register.password.unmet')}>
          {unmet.map((rule) => (
            <ListItem key={rule} disableGutters disablePadding>
              <ListItemIcon sx={{ minWidth: 28 }}>
                {met[rule] ? (
                  <CheckCircleOutlined fontSize="small" aria-hidden />
                ) : (
                  <RadioButtonUnchecked fontSize="small" aria-hidden />
                )}
              </ListItemIcon>
              <ListItemText
                slotProps={{ primary: { variant: 'body2' } }}
                primary={t(`register.password.rule.${rule}`)}
              />
            </ListItem>
          ))}
        </List>
      )}
    </Box>
  );
}

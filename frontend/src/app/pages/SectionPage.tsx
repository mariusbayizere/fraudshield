import Typography from '@mui/material/Typography';
import type { ParseKeys } from 'i18next';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '../../design-system/components/ScreenStates/ScreenStates';

/**
 * A section whose screen is not built yet. It says so plainly rather than showing fake data;
 * each D.3 step replaces one of these with the real screen.
 */
export default function SectionPage({ title }: { title: ParseKeys }) {
  const { t } = useTranslation();
  return (
    <>
      <Typography variant="h5" component="h1" sx={{ mb: 2 }}>
        {t(title)}
      </Typography>
      <EmptyState title={t('shell.notBuiltTitle')} description={t('shell.notBuiltDescription')} />
    </>
  );
}

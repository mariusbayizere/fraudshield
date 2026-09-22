import FormControl from '@mui/material/FormControl';
import InputLabel from '@mui/material/InputLabel';
import NativeSelect from '@mui/material/NativeSelect';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { ENDONYM, LANGUAGES, isLanguage } from '../i18n/languages';
import { rememberLanguage } from './preferences';

/**
 * Language choice, each language named in itself (D-43). A native select: the platform's own
 * picker on phones, and no menu or popover code in the initial bundle (D-39).
 */
export function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  const id = useId();
  const current = isLanguage(i18n.resolvedLanguage) ? i18n.resolvedLanguage : 'en';
  return (
    <FormControl size="small" sx={{ minWidth: 150 }}>
      <InputLabel variant="standard" htmlFor={id}>
        {t('shell.language')}
      </InputLabel>
      <NativeSelect
        id={id}
        value={current}
        onChange={(event) => {
          const next = event.target.value;
          if (!isLanguage(next)) return;
          rememberLanguage(next);
          void i18n.changeLanguage(next);
        }}
      >
        {LANGUAGES.map((language) => (
          <option key={language} value={language} lang={language}>
            {ENDONYM[language]}
          </option>
        ))}
      </NativeSelect>
    </FormControl>
  );
}

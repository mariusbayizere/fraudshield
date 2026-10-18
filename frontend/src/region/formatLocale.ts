import { useTranslation } from 'react-i18next';
import { useRegion } from './RegionContext';

/**
 * The locale numbers and times are written in: the UI language in the region's country.
 *
 * Kept out of RegionContext.tsx, which the initial bundle needs for its provider: only screens
 * format numbers, and this hook is what brings react-i18next's useTranslation with it (D-39).
 */
export function useFormatLocale(): string {
  const { i18n } = useTranslation();
  const { region } = useRegion();
  return `${i18n.resolvedLanguage ?? i18n.language}-${region.country}`;
}

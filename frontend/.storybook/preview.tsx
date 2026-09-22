import type { Preview } from '@storybook/react-vite';
import { LANGUAGES, isLanguage, type Language } from '../src/i18n/languages';
import { Providers } from '../src/test/Providers';
import { testI18n } from '../src/test/i18n';

const catalogues = Object.fromEntries(
  LANGUAGES.map((language) => [language, testI18n(language)]),
) as Record<Language, ReturnType<typeof testI18n>>;

const preview: Preview = {
  globalTypes: {
    theme: {
      description: 'Colour scheme',
      toolbar: { title: 'Theme', icon: 'mirror', items: ['light', 'dark'], dynamicTitle: true },
    },
    language: {
      description: 'UI language (D-43)',
      toolbar: { title: 'Language', icon: 'globe', items: [...LANGUAGES], dynamicTitle: true },
    },
    direction: {
      description: 'Text direction (ADR 0023)',
      toolbar: { title: 'Direction', icon: 'transfer', items: ['ltr', 'rtl'], dynamicTitle: true },
    },
  },
  initialGlobals: { theme: 'light', language: 'en', direction: 'ltr' },
  decorators: [
    (Story, context) => {
      const { theme, language, direction } = context.globals;
      return (
        // Stories render with Country Z's region, the synthetic pack (ADR 0023).
        <Providers
          mode={theme === 'dark' ? 'dark' : 'light'}
          direction={direction === 'rtl' ? 'rtl' : 'ltr'}
          i18n={catalogues[isLanguage(language) ? language : 'en']}
        >
          <Story />
        </Providers>
      );
    },
  ],
  parameters: {
    layout: 'padded',
    // Axe in the Storybook panel fails a story on any violation, as the component tests do.
    a11y: { test: 'error' },
  },
};

export default preview;

import type { Preview } from '@storybook/react-vite';
import { ThemeRoot } from '../src/design-system/ThemeRoot';
import type { ColourMode } from '../src/design-system/tokens';

const preview: Preview = {
  globalTypes: {
    theme: {
      description: 'Colour scheme',
      toolbar: { title: 'Theme', icon: 'mirror', items: ['light', 'dark'], dynamicTitle: true },
    },
  },
  initialGlobals: { theme: 'light' },
  decorators: [
    (Story, context) => {
      const mode: ColourMode = context.globals['theme'] === 'dark' ? 'dark' : 'light';
      return (
        <ThemeRoot mode={mode}>
          <Story />
        </ThemeRoot>
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

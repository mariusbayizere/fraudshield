import { render, type RenderResult } from '@testing-library/react';
import type { ReactElement } from 'react';
import { ThemeRoot } from '../design-system/ThemeRoot';
import type { ColourMode } from '../design-system/tokens';

/** Renders inside the real theme, so a test sees the tokens a user sees. */
export function renderThemed(ui: ReactElement, mode: ColourMode = 'light'): RenderResult {
  return render(<ThemeRoot mode={mode}>{ui}</ThemeRoot>);
}

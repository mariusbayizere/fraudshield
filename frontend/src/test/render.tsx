import { render, type RenderResult } from '@testing-library/react';
import type { ReactElement } from 'react';
import type { ColourMode } from '../design-system/tokens';
import { Providers, type ProviderOptions } from './Providers';

/** Renders inside the real theme, catalogues and a region, so a test sees what a user sees. */
export function renderThemed(
  ui: ReactElement,
  options: ColourMode | ProviderOptions = {},
): RenderResult {
  const resolved = typeof options === 'string' ? { mode: options } : options;
  return render(<Providers {...resolved}>{ui}</Providers>);
}

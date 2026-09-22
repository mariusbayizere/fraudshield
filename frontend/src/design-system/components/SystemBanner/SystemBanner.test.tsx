import { screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { renderThemed } from '../../../test/render';
import { CONDITION_SEVERITY, SYSTEM_CONDITIONS, SystemBanners } from './SystemBanner';

describe('SystemBanners', () => {
  it('shows one polite banner per active condition, in contract order', () => {
    renderThemed(<SystemBanners conditions={['REALTIME_PAUSED', 'ML_UNAVAILABLE']} />);
    const banners = screen.getAllByRole('status');
    expect(banners.map((b) => b.dataset['condition'])).toEqual([
      'ML_UNAVAILABLE',
      'REALTIME_PAUSED',
    ]);
    expect(banners[1]).toHaveTextContent('Real-time paused — refreshing every 60 s');
  });

  it('shows nothing when the system is healthy', () => {
    const { container } = renderThemed(<SystemBanners conditions={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('covers exactly the conditions the OpenAPI contract can send', () => {
    const contract = readFileSync(
      join(import.meta.dirname, '../../../../../contracts/openapi/fraudshield-api.yaml'),
      'utf8',
    );
    const match = /degraded_modes:[\s\S]*?enum: \[([^\]]+)\]/.exec(contract);
    const enumValues = match?.[1]?.split(',').map((v) => v.trim());
    expect(enumValues).toEqual([...SYSTEM_CONDITIONS]);
    expect(Object.keys(CONDITION_SEVERITY)).toEqual([...SYSTEM_CONDITIONS]);
  });
});

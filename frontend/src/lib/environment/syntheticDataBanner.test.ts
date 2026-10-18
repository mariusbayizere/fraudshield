import { describe, expect, it } from 'vitest';

import {
  parseDeploymentEnvironment,
  SYNTHETIC_DATA_BANNER,
  syntheticDataBanner,
} from './syntheticDataBanner';

describe('synthetic-data banner (D-21, ADR 0019)', () => {
  it('uses the exact wording required by D-21', () => {
    expect(SYNTHETIC_DATA_BANNER).toBe('SYNTHETIC DATA — NOT FOR PRODUCTION');
  });

  it('shows the banner whenever the deployment reports synthetic data', () => {
    const environment = parseDeploymentEnvironment({ synthetic_data: true });
    expect(syntheticDataBanner(environment)).toBe(SYNTHETIC_DATA_BANNER);
  });

  it('shows no banner for a deployment with real data', () => {
    expect(syntheticDataBanner(parseDeploymentEnvironment({ synthetic_data: false }))).toBeNull();
  });

  it.each([null, [], 'true', true, {}, { synthetic_data: 'true' }, { synthetic_data: 1 }])(
    'rejects a malformed body %j',
    (body) => {
      expect(() => parseDeploymentEnvironment(body)).toThrow(TypeError);
    },
  );

  it('rejects extra fields, which the contract forbids', () => {
    expect(() => parseDeploymentEnvironment({ synthetic_data: true, region: 'x' })).toThrow(
      TypeError,
    );
  });
});

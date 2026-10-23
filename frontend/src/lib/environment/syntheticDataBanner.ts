/**
 * Synthetic-data banner (D-21, ADR 0019). Development and demo deployments run on synthetic data
 * only and must say so on every page, including the login page. The flag comes from the public
 * GET /api/v1/environment endpoint; the React shell that renders the banner arrives in M8.
 */

export const SYNTHETIC_DATA_BANNER = 'SYNTHETIC DATA — NOT FOR PRODUCTION';

/** Response body of GET /api/v1/environment (contracts/openapi, DeploymentEnvironment). */
export interface DeploymentEnvironment {
  readonly synthetic_data: boolean;
}

/**
 * Parses the endpoint's body strictly: exactly one boolean field. Anything else is rejected, so a
 * changed contract is noticed instead of silently hiding the banner.
 */
export function parseDeploymentEnvironment(body: unknown): DeploymentEnvironment {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw new TypeError('environment response is not an object');
  }
  const keys = Object.keys(body);
  const flag: unknown = (body as Record<string, unknown>)['synthetic_data'];
  if (keys.length !== 1 || typeof flag !== 'boolean') {
    throw new TypeError('environment response must be exactly {"synthetic_data": boolean}');
  }
  return { synthetic_data: flag };
}

/** The banner text to show, or null when the deployment holds real data. */
export function syntheticDataBanner(environment: DeploymentEnvironment): string | null {
  return environment.synthetic_data ? SYNTHETIC_DATA_BANNER : null;
}

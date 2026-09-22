type Schemas = typeof import('./generated/zod.gen');
export type SchemaName = {
  [K in keyof Schemas]: Schemas[K] extends { parse: (data: unknown) => unknown } ? K : never;
}[keyof Schemas];

/** H.4: responses are checked against the contract in development and tests (ADR 0080 §3). */
const VALIDATE = import.meta.env.DEV || import.meta.env.MODE === 'test';

/**
 * Returns `data` unchanged, after checking it against the named generated schema when running in
 * development or tests. The schemas are imported only inside that branch, so a production build
 * drops both the check and the schemas (D-39); productionBundle.test.ts verifies it.
 */
export async function checked<T>(schema: SchemaName, data: T): Promise<T> {
  if (VALIDATE) {
    const schemas = await import('./generated/zod.gen');
    schemas[schema].parse(data);
  }
  return data;
}

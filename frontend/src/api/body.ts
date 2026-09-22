/**
 * The body a generated call returned, or undefined when the call failed or sent none.
 *
 * The generated types promise a body on success, but a proxy, a 204 or a truncated response can
 * deliver none, and production does not run the schema check (ADR 0080 §3). This is the one
 * place that says so.
 */
export function bodyOf<T>(result: { data?: T; error?: unknown }): T | undefined {
  if (result.error !== undefined) return undefined;
  return result.data;
}

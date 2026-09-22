import * as z from 'zod/mini';

/**
 * Where to go after signing in. Only a path within this console is accepted: an absolute URL in
 * a link would let someone send staff to another site through our own login page.
 */
export const loginSearch = z.object({
  next: z.catch(
    z.optional(z.string().check(z.regex(/^\/(?!\/)[^\s]*$/), z.maxLength(2048))),
    undefined,
  ),
});
export type LoginSearch = z.infer<typeof loginSearch>;

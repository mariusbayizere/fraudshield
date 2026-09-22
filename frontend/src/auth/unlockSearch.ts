import * as z from 'zod/mini';

/** The single-use token from the emailed link; its shape is the contract's. */
export const unlockSearch = z.object({
  token: z.catch(z.optional(z.string().check(z.regex(/^[A-Za-z0-9_-]{22,128}$/))), undefined),
});
export type UnlockSearch = z.infer<typeof unlockSearch>;

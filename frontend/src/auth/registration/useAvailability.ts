import { useQuery } from '@tanstack/react-query';
import { bodyOf } from '../../api/body';
import { checkAvailability } from '../../api/generated/sdk.gen';

/** SRS 5.3: the form asks after the analyst stops typing, not on every keystroke. */
export const AVAILABILITY_DEBOUNCE_MS = 500;

export type AvailabilityState = 'idle' | 'checking' | 'available' | 'taken' | 'unknown';

/**
 * Whether an email or employee ID is still free. An answer that cannot be read leaves the field
 * alone: the server checks again on submit, and a failed lookup must not block a registration.
 */
export function useAvailability(
  field: 'email' | 'employee_id',
  value: string,
  enabled: boolean,
): AvailabilityState {
  const query = useQuery({
    queryKey: ['availability', field, value],
    enabled: enabled && value !== '',
    retry: false,
    staleTime: 60_000,
    queryFn: async () => {
      const answer = bodyOf(await checkAvailability({ query: { field, value } }));
      if (answer === undefined) throw new Error('availability could not be read');
      return answer.available;
    },
  });
  if (!enabled || value === '') return 'idle';
  if (query.isFetching) return 'checking';
  if (query.isError) return 'unknown';
  if (query.data === undefined) return 'idle';
  return query.data ? 'available' : 'taken';
}

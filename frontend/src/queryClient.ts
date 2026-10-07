import { QueryClient } from '@tanstack/react-query';

/**
 * The app's single TanStack Query client.
 *
 * Lives in its own module (rather than main.tsx) so non-component code can
 * import it — e.g. the auth store, which clears the whole server-state cache
 * on logout so no other user's data lingers in memory.
 */
export const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
});

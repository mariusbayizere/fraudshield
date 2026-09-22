import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

/** Starts the development mocks. Only ever imported behind `import.meta.env.DEV`. */
export async function startMocks(): Promise<void> {
  await setupWorker(...handlers).start({ onUnhandledRequest: 'bypass', quiet: true });
}

import '@testing-library/jest-dom/vitest';
import { client } from '../api/generated/client.gen';

// The client's base URL is relative ("/api/v1"), which a browser resolves against its origin.
// Tests run on Node's fetch, which needs an absolute URL.
client.setConfig({ baseUrl: 'http://console.test/api/v1' });

import 'i18next';
import type common from './locales/en/common.json';
import type designSystem from './locales/en/designSystem.json';

// English is the source catalogue: a key missing from it is a type error at every t() call.
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: { common: typeof common; designSystem: typeof designSystem };
  }
}

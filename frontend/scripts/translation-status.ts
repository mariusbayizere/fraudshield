// The translation-status report D-43 asks for: per language and namespace, how many strings a
// native speaker has reviewed and how many are machine drafts. Run with `pnpm i18n:status`.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

type Status = Record<string, Record<string, Record<string, string>>>;
const status = JSON.parse(
  readFileSync(join(import.meta.dirname, '../src/i18n/translation-status.json'), 'utf8'),
) as Status;

for (const [language, namespaces] of Object.entries(status)) {
  for (const [namespace, keys] of Object.entries(namespaces)) {
    const values = Object.values(keys);
    const reviewed = values.filter((v) => v === 'reviewed').length;
    console.log(
      `${language} ${namespace}: ${String(reviewed)} reviewed, ${String(values.length - reviewed)} machine_draft`,
    );
  }
}

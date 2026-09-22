// Writes src/region/generated/packs.json from the dataset's country packs (ADR 0023, ADR 0080).
// Run with `pnpm region`; region.test.ts fails if the committed file is stale.
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse } from 'yaml';
import type { Region } from '../src/region/region.ts';

export const PACK_DIR = join(import.meta.dirname, '../../dataset/generator/params/countries');
export const OUTPUT = join(import.meta.dirname, '../src/region/generated/packs.json');

interface Parameter {
  value: unknown;
}

function read(file: string): Region {
  const pack = parse(readFileSync(join(PACK_DIR, file), 'utf8')) as {
    parameters: Record<string, Parameter | undefined>;
  };
  const value = (name: string): unknown => {
    const parameter = pack.parameters[name];
    if (parameter === undefined) throw new Error(`${file} has no ${name}`);
    return parameter.value;
  };
  const currency = value('currency');
  const minor = value('currency_minor_units');
  const offset = value('utc_offset_hours');
  if (typeof currency !== 'string' || !/^[A-Z]{3}$/.test(currency)) {
    throw new Error(`${file}: currency must be an ISO 4217 code`);
  }
  if (typeof minor !== 'number' || !Number.isInteger(minor) || minor < 0) {
    throw new Error(`${file}: currency_minor_units must be a whole number`);
  }
  if (typeof offset !== 'number') throw new Error(`${file}: utc_offset_hours must be a number`);
  return {
    country: file.replace(/\.yaml$/, ''),
    currency,
    currencyMinorUnits: minor,
    utcOffsetHours: offset,
  };
}

export function renderPacks(): string {
  const files = readdirSync(PACK_DIR)
    .filter((f) => /^[A-Z]{2}\.yaml$/.test(f))
    .sort();
  const packs = Object.fromEntries(files.map((f) => [f.replace(/\.yaml$/, ''), read(f)]));
  return `${JSON.stringify(packs, null, 2)}\n`;
}

if (process.argv[1] === import.meta.filename) {
  writeFileSync(OUTPUT, renderPacks());
  console.log(`wrote ${OUTPUT}`);
}

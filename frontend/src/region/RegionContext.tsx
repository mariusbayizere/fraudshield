import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { minorUnitsByCurrency, PACKS } from './packs';
import type { Region } from './region';

interface RegionValue {
  region: Region;
  minorUnits: ReadonlyMap<string, number>;
}

const RegionContext = createContext<RegionValue | null>(null);

/** Provides the deployment's region. There is no default: a missing region is a wiring bug. */
export function RegionProvider({
  region,
  packs = PACKS,
  children,
}: {
  region: Region;
  packs?: Readonly<Record<string, Region>>;
  children: ReactNode;
}) {
  const value = useMemo(
    () => ({ region, minorUnits: minorUnitsByCurrency({ ...packs, [region.country]: region }) }),
    [region, packs],
  );
  return <RegionContext.Provider value={value}>{children}</RegionContext.Provider>;
}

export function useRegion(): RegionValue {
  const value = useContext(RegionContext);
  if (value === null) throw new Error('useRegion() needs a RegionProvider above it');
  return value;
}

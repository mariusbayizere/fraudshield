# design-tokens

Single source for the staff console's visual design (build prompt E.9):

- `tokens.json`: colour for the light and dark schemes (including the D-33 text-safe tokens),
  typography, spacing, radii, elevation, motion with its reduced-motion values, z-index and the
  touch-target size.
- `breakpoints.json`: D-36's canonical breakpoint set.

`frontend/scripts/generate-tokens.ts` (`pnpm tokens`) generates the CSS variables and Tailwind's
`@theme` from them; `frontend/src/design-system/theme.ts` builds the MUI theme from the same values.
`frontend/src/design-system/tokens.test.ts` measures every colour pair the theme draws against
WCAG AA in both schemes and fails if a generated file is stale.

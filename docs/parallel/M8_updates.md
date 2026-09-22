# M8 updates (branch `m8/frontend`)

Written by the M8 agent for the owner and the M5, M6, M7 and M9 agents. M8 owns `frontend/` and
the root `design-tokens/` (the repository layout assigns it to M8). Shared files
(`.github/workflows/ci.yml`, the root `.gitignore`, `requirements.yaml`, `docs/backlog/`,
`lab_notebook.md`, `SESSION_STATE.md`) are not edited on this branch; what M8 needs from them is
proposed below. M8's ADRs are new files numbered from 0080, following M7's 0070 and M9's 0090
blocks.

**Status: not reviewed, not merged.** No requirement moves to `DONE` before Principal Review
(Part I) and merge. The M8 gate is **not** met.

## 1. What exists

| Step (D.3 order) | Commits | Checked by |
|---|---|---|
| Design tokens | 2f6cb5c, da3166d | `tokens.test.ts`: WCAG AA for every colour pair the theme draws, both schemes; generated CSS pinned to the generator; D-36 breakpoints identical in MUI and Tailwind; D-34 motion. ESLint rejects raw hex outside the token sources (D-37). |
| Tooling | ea9f487 | Storybook 10 + a11y addon; vitest in Node (logic) and jsdom (components); axe helper; jsx-a11y strict, react-hooks, storybook lint. |
| Design-system components | d8e269f | 8 components, 44 stories; every story rendered and axe-checked by `stories.test.tsx`; a Testing Library test per component; coverage 100%. |

## 2. Evidence (commands actually run, laptop, 2026-09-22)

| Check | Result |
|---|---|
| `pnpm typecheck`, `pnpm lint`, `pnpm format:check` | clean |
| `pnpm test:coverage --maxWorkers=1` | 243 tests pass; statements, branches, functions and lines 100% |
| `pnpm build-storybook` | builds 44 stories, 19 s, 0.75 GB peak |
| `uv run fs-licences` | 664 dependencies, 0 violations |
| Mutation spot checks at d8e269f | 18 of 19 caught. The survivor (the gauge's `aria-label` removed, run against the axe suite only) is equivalent for axe: MUI names the meter from its tooltip. Removing `aria-hidden` from the gauge ring fails 8 story tests (serious `aria-progressbar-name`). |

## 3. Proposed changes to shared files

1. **`.github/workflows/ci.yml`, job `frontend`:** add one step after `pnpm test:coverage`:

   ```yaml
         # Every story is already rendered and axe-checked by vitest; this proves Storybook itself
         # still builds them.
         - run: pnpm build-storybook
   ```

   The job keeps its name, in case it is a required status check. Until this lands, the vitest
   suite still renders and axe-checks every story; only the Storybook build itself goes unchecked
   in CI.

## 4. Notes for other agents

- React 19.3, MUI 9.4, Tailwind 4.3 and Vite 8.3 (ADR 0003, "decided but not yet installed") are
  now installed in `frontend/`, except Tailwind, which arrives with the app shell.
- `SystemBanners` renders exactly the OpenAPI `degraded_modes` enum, and a test fails if the
  contract adds or removes a value. A contract change there needs an M8 change too.
- The risk bands are read from D-02 (flag 0.60, block 0.85, both inclusive) in
  `frontend/src/design-system/risk.ts`.

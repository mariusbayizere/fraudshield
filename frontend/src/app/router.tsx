import {
  createRootRoute,
  createRoute,
  createRouter,
  lazyRouteComponent,
  redirect,
  type AnyRoute,
} from '@tanstack/react-router';
import { AppShell } from './AppShell';
import { alertSearch } from './alertSearch';
import { PUBLIC_PAGES, SECTIONS } from './sections';

const rootRoute = createRootRoute();

/** Pages load as their own chunks, on first visit (D-39). */
const SectionPage = lazyRouteComponent(() => import('./pages/SectionPage'));

const shell = createRoute({ getParentRoute: () => rootRoute, id: 'shell', component: AppShell });

const index = createRoute({
  getParentRoute: () => shell,
  path: '/',
  beforeLoad: () => {
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's redirect
    throw redirect({ to: '/alerts' });
  },
});

const sections: AnyRoute[] = SECTIONS.map((section) =>
  createRoute({
    getParentRoute: () => shell,
    path: section.path,
    ...(section.path === '/alerts' ? { validateSearch: alertSearch } : {}),
    component: () => <SectionPage title={section.title} />,
  }),
);

const alertDetail = createRoute({
  getParentRoute: () => shell,
  path: '/alerts/$alertId',
  validateSearch: alertSearch,
  component: () => <SectionPage title="nav.alerts" />,
});

const publicPages: AnyRoute[] = PUBLIC_PAGES.map((page) =>
  createRoute({
    getParentRoute: () => rootRoute,
    path: page.path,
    component: () => <SectionPage title={page.title} />,
  }),
);

export const routeTree = rootRoute.addChildren([
  shell.addChildren([index, ...sections, alertDetail]),
  ...publicPages,
]);

export function createAppRouter() {
  return createRouter({ routeTree, defaultPreload: 'intent' });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}

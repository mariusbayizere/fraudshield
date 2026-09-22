import {
  createRootRoute,
  createRoute,
  createRouter,
  lazyRouteComponent,
  redirect,
  type AnyRoute,
} from '@tanstack/react-router';
import { loginSearch } from '../auth/loginSearch';
import { alertSearch } from './alertSearch';
import { PUBLIC_PAGES, SECTIONS } from './sections';

const rootRoute = createRootRoute();

// Everything past the providers loads as its own chunk, on first visit (D-39, ADR 0080 §10):
// the shell's navigation, app bar and drawers are not in the initial bundle either.
const SectionPage = lazyRouteComponent(() => import('./pages/SectionPage'));
const AppShell = lazyRouteComponent(() => import('./AppShell'), 'AppShell');
const LoginPage = lazyRouteComponent(() => import('../auth/LoginPage'));
const RegisterPage = lazyRouteComponent(() => import('../auth/registration/RegisterPage'));

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

const login = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  validateSearch: loginSearch,
  component: LoginPage,
});

const registerRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/register',
  component: RegisterPage,
});

const publicPages: AnyRoute[] = PUBLIC_PAGES.filter(
  (page) => page.path !== '/login' && page.path !== '/register',
).map((page) =>
  createRoute({
    getParentRoute: () => rootRoute,
    path: page.path,
    component: () => <SectionPage title={page.title} />,
  }),
);

export const routeTree = rootRoute.addChildren([
  shell.addChildren([index, ...sections, alertDetail]),
  login,
  registerRoute,
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

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
const AlertFeedPage = lazyRouteComponent(() => import('../alerts/AlertFeedPage'));
const AlertDetailPanel = lazyRouteComponent(() => import('../alerts/detail/AlertDetailPanel'));
const AnomalyQueuePage = lazyRouteComponent(
  () => import('../alerts/AlertFeedPage'),
  'AnomalyQueuePage',
);
const EscalationsQueuePage = lazyRouteComponent(
  () => import('../alerts/AlertFeedPage'),
  'EscalationsQueuePage',
);
const SearchPage = lazyRouteComponent(() => import('../alerts/AlertFeedPage'), 'SearchPage');
const MyPerformancePage = lazyRouteComponent(() => import('../performance/MyPerformancePage'));
const SearchDetailPanel = lazyRouteComponent(
  () => import('../alerts/detail/AlertDetailPanel'),
  'SearchDetailPanel',
);
const EscalationDetailPanel = lazyRouteComponent(
  () => import('../alerts/detail/AlertDetailPanel'),
  'EscalationDetailPanel',
);
const AnomalyDetailPanel = lazyRouteComponent(
  () => import('../alerts/detail/AlertDetailPanel'),
  'AnomalyDetailPanel',
);
const AppShell = lazyRouteComponent(() => import('./AppShell'), 'AppShell');
const LoginPage = lazyRouteComponent(() => import('../auth/LoginPage'));
const RegisterPage = lazyRouteComponent(() => import('../auth/registration/RegisterPage'));
const ForgotPasswordPage = lazyRouteComponent(() => import('../auth/ForgotPasswordPage'));
const ResetPasswordPage = lazyRouteComponent(() => import('../auth/ResetPasswordPage'));
const UnlockPage = lazyRouteComponent(() => import('../auth/UnlockPage'));
const PendingApprovalPage = lazyRouteComponent(() => import('../auth/PendingApprovalPage'));

const AUTH_PAGES: { path: string; component: ReturnType<typeof lazyRouteComponent> }[] = [
  { path: '/forgot-password', component: ForgotPasswordPage },
  { path: '/reset-password', component: ResetPasswordPage },
  { path: '/unlock', component: UnlockPage },
  { path: '/pending-approval', component: PendingApprovalPage },
];

const shell = createRoute({ getParentRoute: () => rootRoute, id: 'shell', component: AppShell });

const index = createRoute({
  getParentRoute: () => shell,
  path: '/',
  beforeLoad: () => {
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's redirect
    throw redirect({ to: '/alerts' });
  },
});

// The screens that are built; every other section shows that it is not built yet.
const alerts = createRoute({
  getParentRoute: () => shell,
  path: '/alerts',
  validateSearch: alertSearch,
  component: AlertFeedPage,
});
// D-10's anomaly queue: the same page, cards and drawer as the feed, over queue=anomaly.
const anomalies = createRoute({
  getParentRoute: () => shell,
  path: '/anomalies',
  validateSearch: alertSearch,
  component: AnomalyQueuePage,
});
// FR-04-10's escalations: the same page and drawer over queue=escalated.
const escalations = createRoute({
  getParentRoute: () => shell,
  path: '/escalations',
  validateSearch: alertSearch,
  component: EscalationsQueuePage,
});
const escalationDetail = createRoute({
  getParentRoute: () => escalations,
  path: '$alertId',
  component: EscalationDetailPanel,
});
// FR-04-09's search: the same page and drawer, under a criteria form.
const searchRoute = createRoute({
  getParentRoute: () => shell,
  path: '/search',
  validateSearch: alertSearch,
  component: SearchPage,
});
const searchDetail = createRoute({
  getParentRoute: () => searchRoute,
  path: '$alertId',
  component: SearchDetailPanel,
});
// FR-04-11's own figures.
const myPerformance = createRoute({
  getParentRoute: () => shell,
  path: '/me/performance',
  component: MyPerformancePage,
});
const builtSections = new Set([
  '/alerts',
  '/anomalies',
  '/escalations',
  '/search',
  '/me/performance',
]);

const sections: AnyRoute[] = SECTIONS.filter((section) => !builtSections.has(section.path)).map(
  (section) =>
    createRoute({
      getParentRoute: () => shell,
      path: section.path,
      component: () => <SectionPage title={section.title} />,
    }),
);

// The investigation drawer is a child of the feed, so the feed stays on screen and usable
// behind it (UX-DASH-04), and a deep link opens both (E.9).
const alertDetail = createRoute({
  getParentRoute: () => alerts,
  path: '$alertId',
  component: AlertDetailPanel,
});

const anomalyDetail = createRoute({
  getParentRoute: () => anomalies,
  path: '$alertId',
  component: AnomalyDetailPanel,
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

const authPages: AnyRoute[] = AUTH_PAGES.map((page) =>
  createRoute({ getParentRoute: () => rootRoute, path: page.path, component: page.component }),
);

const built = new Set(['/login', '/register', ...AUTH_PAGES.map((page) => page.path)]);

const publicPages: AnyRoute[] = PUBLIC_PAGES.filter((page) => !built.has(page.path)).map((page) =>
  createRoute({
    getParentRoute: () => rootRoute,
    path: page.path,
    component: () => <SectionPage title={page.title} />,
  }),
);

export const routeTree = rootRoute.addChildren([
  shell.addChildren([
    index,
    alerts.addChildren([alertDetail]),
    anomalies.addChildren([anomalyDetail]),
    escalations.addChildren([escalationDetail]),
    searchRoute.addChildren([searchDetail]),
    myPerformance,
    ...sections,
  ]),
  login,
  registerRoute,
  ...authPages,
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

import type { ParseKeys } from 'i18next';

export type SectionGroup = 'analyst' | 'riskOfficer' | 'admin' | 'settings';

export interface Section {
  path: string;
  group: SectionGroup;
  /** Catalogue key for the section's name in the navigation and the page title. */
  title: ParseKeys;
  /** One of the three to four analyst destinations on the phone's bottom bar (05B A.7). */
  primary?: true;
}

/** The staff console's sections (E.9's information architecture), in navigation order. */
export const SECTIONS: readonly Section[] = [
  { path: '/alerts', group: 'analyst', title: 'nav.alerts', primary: true },
  { path: '/anomalies', group: 'analyst', title: 'nav.anomalies', primary: true },
  { path: '/escalations', group: 'analyst', title: 'nav.escalations' },
  { path: '/search', group: 'analyst', title: 'nav.search', primary: true },
  { path: '/me/performance', group: 'analyst', title: 'nav.performance' },
  { path: '/portfolio', group: 'riskOfficer', title: 'nav.portfolio' },
  { path: '/model-performance', group: 'riskOfficer', title: 'nav.modelPerformance' },
  { path: '/campaigns', group: 'riskOfficer', title: 'nav.campaigns' },
  { path: '/rules', group: 'riskOfficer', title: 'nav.rules' },
  { path: '/thresholds', group: 'riskOfficer', title: 'nav.thresholds' },
  { path: '/reports/sar', group: 'riskOfficer', title: 'nav.sar' },
  { path: '/overrides', group: 'riskOfficer', title: 'nav.overrides' },
  { path: '/admin/users', group: 'admin', title: 'nav.users' },
  { path: '/admin/approvals', group: 'admin', title: 'nav.approvals' },
  { path: '/admin/models', group: 'admin', title: 'nav.models' },
  { path: '/admin/datasets', group: 'admin', title: 'nav.datasets' },
  { path: '/admin/health', group: 'admin', title: 'nav.health' },
  { path: '/admin/audit', group: 'admin', title: 'nav.audit' },
  { path: '/admin/api-keys', group: 'admin', title: 'nav.apiKeys' },
  { path: '/admin/circuit-breakers', group: 'admin', title: 'nav.circuitBreakers' },
  { path: '/admin/ip-allowlist', group: 'admin', title: 'nav.ipAllowlist' },
  { path: '/settings', group: 'settings', title: 'nav.settings' },
];

/** Public pages, outside the shell (E.9). */
export const PUBLIC_PAGES = [
  { path: '/login', title: 'nav.login' },
  { path: '/register', title: 'nav.register' },
  { path: '/forgot-password', title: 'nav.forgotPassword' },
  { path: '/reset-password', title: 'nav.resetPassword' },
  { path: '/unlock', title: 'nav.unlock' },
  { path: '/pending-approval', title: 'nav.pendingApproval' },
] as const satisfies readonly { path: string; title: ParseKeys }[];

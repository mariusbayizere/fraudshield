import raw from '../../design-tokens/tokens.json' with { type: 'json' };

/** The two colour schemes. Light is the SRS's; dark exists for long shifts (build prompt E.9). */
export type ColourMode = 'light' | 'dark';

export const RISK_TIERS = ['high', 'medium', 'low'] as const;
export type RiskTier = (typeof RISK_TIERS)[number];

/** The six channels, spelled as the API spells them. */
export const CHANNELS = [
  'MOBILE_MONEY',
  'CARD',
  'USSD',
  'AGENT_BANKING',
  'ONLINE',
  'BANK_TRANSFER',
] as const;
export type Channel = (typeof CHANNELS)[number];

export interface RiskColours {
  bg: string;
  border: string;
  badge: string;
  /** D-33's text-safe colour: the only one text may be drawn in, on or in this tier. */
  text: string;
}

export interface ChannelColours {
  /** The SRS hue, for fills, bars and icons (non-text contrast only). */
  fill: string;
  /** The chip background white text sits on; a text-safe token where the hue alone fails. */
  chip: string;
}

export interface Palette {
  brand: { navy: string; accent: string; onBrand: string };
  surface: {
    background: string;
    paper: string;
    divider: string;
    textPrimary: string;
    textSecondary: string;
  };
  risk: Record<RiskTier, RiskColours>;
  shap: { increasesFraud: string; decreasesFraud: string };
  channel: Record<Channel, ChannelColours>;
  chipText: string;
  focusRing: string;
}

export type BreakpointName = 'fp' | 'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'xxl' | 'xxxl';

export interface Tokens {
  color: Record<ColourMode, Palette>;
  typography: {
    fontFamily: string;
    fontFamilyMono: string;
    numeric: string;
    sizes: Record<'xs' | 'sm' | 'md' | 'lg' | 'xl' | 'xxl' | 'display', number>;
    weights: Record<'regular' | 'medium' | 'semibold' | 'bold', number>;
    lineHeight: Record<'tight' | 'normal', number>;
  };
  spacing: { unit: number };
  radius: Record<'sm' | 'md' | 'lg' | 'pill', number>;
  elevation: Record<'none' | 'raised' | 'overlay', string>;
  motion: {
    durationMs: Record<'short' | 'standard' | 'drawer' | 'pulseCycle', number>;
    pulseCycles: number;
    reducedDurationMs: number;
  };
  zIndex: Record<'appBar' | 'drawer' | 'modal' | 'snackbar' | 'tooltip', number>;
  breakpoints: Record<BreakpointName, number>;
  touchTargetPx: number;
}

/** D-36's canonical breakpoint names, smallest first. */
export const BREAKPOINT_ORDER: readonly BreakpointName[] = [
  'fp',
  'xs',
  'sm',
  'md',
  'lg',
  'xl',
  'xxl',
  'xxxl',
];

export const tokens: Tokens = raw;

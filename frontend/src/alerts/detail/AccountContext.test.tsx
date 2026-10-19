import { fireEvent, screen, within } from '@testing-library/react';
import { userEvent } from '@testing-library/user-event';
import type {
  AccountNetwork,
  AccountTransaction,
  BehaviourProfile,
} from '../../api/generated/types.gen';
import {
  zAccountNetwork,
  zAccountTransactionPage,
  zBehaviourProfile,
} from '../../api/generated/zod.gen';
import { tokens } from '../../design-system/tokens';
import { axeViolations } from '../../test/axe';
import { renderThemed } from '../../test/render';
import { BehaviourTab } from './BehaviourTab';
import { HistoryTab } from './HistoryTab';
import { layout, NetworkTab, ZOOM } from './NetworkTab';

// Every fixture is parsed by the contract's generated schema, so one the contract would reject
// fails here rather than test a shape the server cannot send.
const ACCOUNT = 'tok_flaggedAccount000000000001';
const DIRECT_A = 'tok_directCounterparty00000001';
const DIRECT_B = 'tok_directCounterparty00000002';
const SECOND_A = 'tok_secondDegreeAccount0000001';
const SECOND_B = 'tok_secondDegreeAccount0000002';
const ABSENT = 'tok_notInTheNodeList0000000001';

const THIS_TRANSACTION = '3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11';

function transaction(over: Partial<AccountTransaction>): AccountTransaction {
  return {
    transaction_id: crypto.randomUUID(),
    amount: { amount: '15000', currency: 'RWF' },
    channel: 'MOBILE_MONEY',
    transaction_timestamp: '2026-09-17T08:15:30Z',
    risk_score: 0.12,
    risk_tier: 'LOW',
    outcome: 'APPROVED',
    ...over,
  };
}

const history = zAccountTransactionPage.parse({
  items: [
    transaction({
      transaction_id: THIS_TRANSACTION,
      amount: { amount: '250000', currency: 'RWF' },
      channel: 'USSD',
      transaction_timestamp: '2026-09-17T08:15:30Z',
      risk_score: 0.71,
      risk_tier: 'MEDIUM',
      outcome: 'PENDING',
    }),
    transaction({
      transaction_timestamp: '2026-09-16T10:00:00Z',
      risk_score: 0.93,
      risk_tier: 'HIGH',
      outcome: 'BLOCKED',
    }),
    transaction({ transaction_timestamp: '2026-09-15T10:00:00Z' }),
  ],
  next_cursor: null,
}).items;

const hourly = Array.from({ length: 24 }, (_, h) => (h === 8 ? 12 : h === 9 ? 6 : 0));
const profile: BehaviourProfile = zBehaviourProfile.parse({
  window_days: 90,
  hourly_counts: hourly,
  amount_histogram: [
    { lower_rwf: '0', upper_rwf: '10000', count: 80 },
    { lower_rwf: '10000', upper_rwf: '50000', count: 20 },
  ],
  channel_mix: [
    { channel: 'MOBILE_MONEY', count: 75 },
    { channel: 'USSD', count: 25 },
  ],
  comparison: [
    { attribute: 'local_hour', this_transaction: 2, normal: '07-21', anomalous: true },
    { attribute: 'channel', this_transaction: 'USSD', normal: 'USSD', anomalous: false },
    { attribute: 'device_age_days', this_transaction: null, normal: 340, anomalous: false },
  ],
});

const network: AccountNetwork = zAccountNetwork.parse({
  nodes: [
    { token: SECOND_A, degree: 2, confirmed_fraud: false },
    { token: ACCOUNT, degree: 0, confirmed_fraud: false },
    { token: DIRECT_A, degree: 1, confirmed_fraud: false },
    { token: SECOND_B, degree: 2, confirmed_fraud: true },
    { token: DIRECT_B, degree: 1, confirmed_fraud: true },
  ],
  edges: [
    { source: ACCOUNT, target: DIRECT_A, transaction_count: 8 },
    { source: ACCOUNT, target: DIRECT_B, transaction_count: 2 },
    // SECOND_A hangs off DIRECT_B and SECOND_B off DIRECT_A, the reverse of the node order.
    { source: SECOND_A, target: DIRECT_B, transaction_count: 1 },
    { source: DIRECT_A, target: SECOND_B, transaction_count: 4 },
    { source: DIRECT_A, target: ABSENT, transaction_count: 3 },
  ],
});

describe('the account history (FR-04-06)', () => {
  it('[FR-04-06] lists each transaction with its amount, channel, time, score, tier and outcome', async () => {
    const { container } = renderThemed(
      <HistoryTab items={history} transactionId={THIS_TRANSACTION} />,
    );
    const list = screen.getByRole('list', {
      name: "The account's most recent transactions, newest first",
    });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    const [first, second] = items;
    if (first === undefined || second === undefined) throw new Error('three items');
    expect(first).toHaveTextContent('250,000');
    expect(within(first).getByText('USSD')).toBeInTheDocument();
    expect(first.querySelector('time')).toHaveAttribute('datetime', '2026-09-17T08:15:30Z');
    expect(first).toHaveTextContent('Risk score 71%');
    expect(within(first).getByText('REVIEW')).toBeInTheDocument();
    expect(within(first).getByText('Pending')).toBeInTheDocument();
    expect(within(second).getByText('Blocked')).toBeInTheDocument();
    expect(within(second).getByText('HIGH RISK')).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('keeps the order the server sends, most recent first', () => {
    // Reversed: the console does not re-sort, so a server that broke the order would show it.
    renderThemed(<HistoryTab items={[...history].reverse()} transactionId={THIS_TRANSACTION} />);
    const times = screen
      .getAllByRole('listitem')
      .map((item) => item.querySelector('time')?.getAttribute('datetime'));
    expect(times).toEqual(['2026-09-15T10:00:00Z', '2026-09-16T10:00:00Z', '2026-09-17T08:15:30Z']);
  });

  it("marks the alert's own transaction", () => {
    renderThemed(<HistoryTab items={history} transactionId={THIS_TRANSACTION} />);
    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveAttribute('aria-current', 'true');
    expect(items[0]).toHaveTextContent('This alert');
    expect(items[1]).not.toHaveAttribute('aria-current');
    expect(items[1]).not.toHaveTextContent('This alert');
  });

  it('gives each outcome its own icon, so it reads without colour', () => {
    renderThemed(<HistoryTab items={history} transactionId={THIS_TRANSACTION} />);
    const [pending, blocked, approved] = screen.getAllByRole('listitem');
    if (pending === undefined || blocked === undefined || approved === undefined) {
      throw new Error('three items');
    }
    expect(within(pending).getByTestId('HourglassEmptyRoundedIcon')).toBeInTheDocument();
    expect(within(blocked).getByTestId('BlockRoundedIcon')).toBeInTheDocument();
    expect(within(approved).getByTestId('CheckRoundedIcon')).toBeInTheDocument();
  });

  it('says when the account has no transactions', () => {
    renderThemed(<HistoryTab items={[]} transactionId={THIS_TRANSACTION} />);
    expect(screen.getByText('No transactions on this account.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
  });
});

describe('the behavioural fingerprint (FR-04-07)', () => {
  it('[FR-04-07] shows the four panels', async () => {
    const { container } = renderThemed(<BehaviourTab profile={profile} />);
    for (const name of [
      "This transaction against the account's normal",
      'Transactions by hour of day',
      'Transactions by amount',
      'Transactions by channel',
    ]) {
      expect(screen.getByRole('heading', { level: 3, name })).toBeInTheDocument();
      expect(screen.getByRole('table', { name })).toBeInTheDocument();
    }
    expect(screen.getByText("Compared with the account's last 90 days.")).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('[FR-04-07] marks the unusual fields in amber, with an icon and a word', () => {
    renderThemed(<BehaviourTab profile={profile} />);
    const table = screen.getByRole('table', {
      name: "This transaction against the account's normal",
    });
    const unusual = within(table).getByRole('row', { name: /local_hour/ });
    const usual = within(table).getByRole('row', { name: /^channel/ });
    const amber = tokens.color.light.risk.medium;
    expect(unusual).toHaveStyle({ backgroundColor: amber.bg });
    expect(unusual).toHaveTextContent('Unusual');
    expect(within(unusual).getByTestId('WarningRoundedIcon')).toBeInTheDocument();
    expect(usual).not.toHaveStyle({ backgroundColor: amber.bg });
    expect(usual).not.toHaveTextContent('Unusual');
    // A missing value is said to be missing.
    expect(within(table).getByRole('row', { name: /device_age_days/ })).toHaveTextContent('none');
  });

  it('writes every hour with its count, and shades it to the busiest hour', () => {
    renderThemed(<BehaviourTab profile={profile} />);
    const table = screen.getByRole('table', { name: 'Transactions by hour of day' });
    expect(within(table).getAllByRole('cell')).toHaveLength(24);
    expect(within(table).getByText('08:00, 12 transactions')).toBeInTheDocument();
    expect(within(table).getByText('09:00, 6 transactions')).toBeInTheDocument();
    expect(within(table).getByText('00:00, 0 transactions')).toBeInTheDocument();
    const shades = within(table).getAllByTestId('hour-shade');
    // rgb() is fully opaque; rgba() carries its alpha; anything else is not a shade at all.
    const opacity = (h: number) => {
      const colour = getComputedStyle(shades[h] as Element).backgroundColor;
      const match = /^rgba?\(\d+,\s*\d+,\s*\d+(?:,\s*([\d.]+))?\)$/.exec(colour);
      return match === null ? Number.NaN : Number(match[1] ?? 1);
    };
    expect(opacity(8)).toBe(1);
    expect(opacity(9)).toBe(0.5);
    expect(opacity(0)).toBe(0);
  });

  it('says when a window has no amounts or channels, rather than draw an empty table', () => {
    renderThemed(<BehaviourTab profile={{ ...profile, amount_histogram: [], channel_mix: [] }} />);
    expect(screen.queryByRole('table', { name: 'Transactions by amount' })).toBeNull();
    expect(screen.queryByRole('table', { name: 'Transactions by channel' })).toBeNull();
    expect(screen.getAllByText('No transactions in this window.')).toHaveLength(2);
  });

  it('draws its bars and shades in the accent, which D-33 measures, in dark mode too', () => {
    renderThemed(<BehaviourTab profile={profile} />, 'dark');
    const accent = tokens.color.dark.brand.accent;
    for (const bar of screen.getAllByTestId('amount-bar')) {
      expect(bar).toHaveStyle({ backgroundColor: accent });
    }
    const busiest = screen.getAllByTestId('hour-shade')[8];
    expect(busiest).toHaveStyle({ backgroundColor: accent });
  });

  it('keeps every row when the server repeats a key', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const row = { attribute: 'local_hour', this_transaction: 2, normal: '07-21', anomalous: true };
    renderThemed(<BehaviourTab profile={{ ...profile, comparison: [row, row] }} />);
    const table = screen.getByRole('table', {
      name: "This transaction against the account's normal",
    });
    expect(within(table).getAllByRole('row', { name: /local_hour/ })).toHaveLength(2);
    expect(error.mock.calls.flat().join(' ')).not.toContain('same key');
    error.mockRestore();
  });

  it('draws the amounts and channels to scale, with each count and share written', () => {
    renderThemed(<BehaviourTab profile={profile} />);
    const amounts = screen.getByRole('table', { name: 'Transactions by amount' });
    const range = within(amounts).getAllByRole('row')[1];
    expect(range).toHaveTextContent('80');
    expect(
      within(amounts)
        .getAllByTestId('amount-bar')
        .map((bar) => getComputedStyle(bar).width),
    ).toEqual(['100%', '25%']);
    const channels = screen.getByRole('table', { name: 'Transactions by channel' });
    const mobile = within(channels).getByRole('row', { name: /Mobile money/ });
    expect(mobile).toHaveTextContent('75');
    expect(mobile).toHaveTextContent('75%');
    expect(within(channels).getByRole('row', { name: /USSD/ })).toHaveTextContent('25%');
  });
});

describe('the transaction network (FR-04-08)', () => {
  /** A node's angle around the centre, from -π to π. */
  const angle = (node: { x: number; y: number } | undefined) =>
    Math.atan2(node?.y ?? 0, node?.x ?? 0);
  const apart = (a: number, b: number) => Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b)));

  it('places the account at the centre, then each ring, each second degree by its counterparty', () => {
    const { nodes: placed, half } = layout(network);
    expect(half).toBe(260);
    const at = new Map(placed.map((n) => [n.token, n]));
    expect(at.get(ACCOUNT)).toMatchObject({ x: 0, y: 0 });
    for (const token of [DIRECT_A, DIRECT_B]) {
      const node = at.get(token);
      expect(Math.hypot(node?.x ?? 0, node?.y ?? 0)).toBeCloseTo(120);
    }
    for (const token of [SECOND_A, SECOND_B]) {
      const node = at.get(token);
      expect(Math.hypot(node?.x ?? 0, node?.y ?? 0)).toBeCloseTo(220);
    }
    // Each second-degree account is drawn within its own counterparty's half of the circle.
    expect(apart(angle(at.get(SECOND_B)), angle(at.get(DIRECT_A)))).toBeLessThan(Math.PI / 2);
    expect(apart(angle(at.get(SECOND_A)), angle(at.get(DIRECT_B)))).toBeLessThan(Math.PI / 2);
    // The same graph is always drawn the same way.
    expect(layout(network).nodes).toEqual(placed);
  });

  it('keeps the accounts off one counterparty beside it, however lopsided the graph', () => {
    // Two counterparties; ten accounts hang off the second, none off the first.
    const token = (kind: string, i: number) => `tok_${kind}${String(i).padStart(24, '0')}`;
    const children = Array.from({ length: 10 }, (_, i) => token('child', i));
    const lopsided = zAccountNetwork.parse({
      nodes: [
        { token: ACCOUNT, degree: 0, confirmed_fraud: false },
        { token: DIRECT_A, degree: 1, confirmed_fraud: false },
        { token: DIRECT_B, degree: 1, confirmed_fraud: false },
        ...children.map((child) => ({ token: child, degree: 2, confirmed_fraud: false })),
      ],
      edges: [
        { source: ACCOUNT, target: DIRECT_A, transaction_count: 1 },
        { source: ACCOUNT, target: DIRECT_B, transaction_count: 1 },
        ...children.map((child) => ({ source: DIRECT_B, target: child, transaction_count: 1 })),
      ],
    });
    const at = new Map(layout(lopsided).nodes.map((n) => [n.token, n]));
    // B's sector is 10/11 of the circle: every child is within half of it of B.
    for (const child of children) {
      expect(apart(angle(at.get(child)), angle(at.get(DIRECT_B)))).toBeLessThanOrEqual(
        (Math.PI * 10) / 11,
      );
    }
    expect(apart(angle(at.get(DIRECT_A)), angle(at.get(DIRECT_B)))).toBeGreaterThan(Math.PI / 2);
    // Each sits within its share of a node's room either side of B, not spread over the sector.
    for (const child of children) {
      const node = at.get(child);
      const radius = Math.hypot(node?.x ?? 0, node?.y ?? 0);
      expect(apart(angle(node), angle(at.get(DIRECT_B)))).toBeLessThanOrEqual(
        (4.5 * 36) / radius + 1e-9,
      );
    }
    // And they are spread evenly about it, not round the whole circle: their offsets from B
    // cancel sideways, and lean its way (about 1.0 here; an even spread round the circle is 0).
    const offsets = children.map((child) => angle(at.get(child)) - angle(at.get(DIRECT_B)));
    expect(offsets.reduce((sum, o) => sum + Math.sin(o), 0)).toBeCloseTo(0);
    expect(offsets.reduce((sum, o) => sum + Math.cos(o), 0)).toBeGreaterThan(0.5);
  });

  it('grows the rings so that 50 accounts never overlap, and the drawing with them', () => {
    const token = (kind: string, i: number) => `tok_${kind}${String(i).padStart(24, '0')}`;
    const direct = Array.from({ length: 30 }, (_, i) => token('direct', i));
    const second = Array.from({ length: 19 }, (_, i) => token('second', i));
    const full = zAccountNetwork.parse({
      nodes: [
        { token: ACCOUNT, degree: 0, confirmed_fraud: false },
        ...direct.map((t) => ({ token: t, degree: 1, confirmed_fraud: false })),
        ...second.map((t) => ({ token: t, degree: 2, confirmed_fraud: false })),
      ],
      edges: second.map((t, i) => ({
        source: direct[i % 3] ?? ACCOUNT,
        target: t,
        transaction_count: 1,
      })),
    });
    const { nodes, half } = layout(full);
    expect(nodes).toHaveLength(50);
    let closest = Infinity;
    for (const [i, a] of nodes.entries()) {
      for (const b of nodes.slice(i + 1))
        closest = Math.min(closest, Math.hypot(a.x - b.x, a.y - b.y));
    }
    // A node is 28 units across; every pair keeps at least that.
    expect(closest).toBeGreaterThanOrEqual(28);
    const furthest = Math.max(...nodes.map((n) => Math.hypot(n.x, n.y)));
    expect(half).toBeGreaterThan(furthest);
  });

  it('[FR-04-08] highlights confirmed fraud in red with a glow, and says so in the table', async () => {
    const { container } = renderThemed(<NetworkTab network={network} />);
    expect(screen.getByText('5 accounts. Confirmed as fraud: 2.')).toBeInTheDocument();
    const drawing = screen.getByRole('group', { name: "Drawing of the account's network" });
    expect(drawing).toHaveAccessibleDescription(
      '5 accounts. Confirmed as fraud: 2. Drag the drawing, or focus it and use the arrow keys, to move around it.',
    );
    const nodes = within(drawing).getAllByTestId('network-node');
    expect(nodes).toHaveLength(5);
    const red = tokens.color.light.risk.high.border;
    for (const node of nodes) {
      const circle = node.querySelector('circle');
      if (node.dataset['fraud'] === 'true') {
        expect(circle).toHaveAttribute('filter', 'url(#fs-network-glow)');
        expect(circle).toHaveAttribute('stroke', red);
        expect(node).toHaveTextContent('!');
      } else {
        expect(circle).not.toHaveAttribute('filter');
        expect(circle).not.toHaveAttribute('stroke', red);
      }
    }
    const accounts = screen.getByRole('table', { name: 'Accounts in the network' });
    expect(within(accounts).getByRole('row', { name: new RegExp(DIRECT_B) })).toHaveTextContent(
      'Direct counterpartyConfirmed fraud',
    );
    expect(within(accounts).getByRole('row', { name: new RegExp(DIRECT_A) })).toHaveTextContent(
      'Direct counterpartyNo',
    );
    expect(within(accounts).getByRole('row', { name: new RegExp(SECOND_B) })).toHaveTextContent(
      'Second degreeConfirmed fraud',
    );
    expect(await axeViolations(container)).toEqual([]);
  });

  it('draws a repeated account once, links it, and lists every copy', () => {
    const repeated = {
      ...network,
      nodes: [...network.nodes, { token: DIRECT_B, degree: 1, confirmed_fraud: true }],
    };
    expect(layout(repeated).nodes).toHaveLength(5);
    renderThemed(<NetworkTab network={repeated} />);
    expect(screen.getAllByTestId('network-node')).toHaveLength(5);
    expect(screen.getAllByTestId('network-edge')).toHaveLength(4);
    const accounts = screen.getByRole('table', { name: 'Accounts in the network' });
    expect(within(accounts).getAllByRole('row', { name: new RegExp(DIRECT_B) })).toHaveLength(2);
  });

  it('draws an account with no counterparties alone at the centre', () => {
    const alone = zAccountNetwork.parse({
      nodes: [{ token: ACCOUNT, degree: 0, confirmed_fraud: false }],
      edges: [],
    });
    expect(layout(alone)).toEqual({ nodes: [{ ...alone.nodes[0], x: 0, y: 0 }], half: 260 });
  });

  it('draws only the links between accounts it has, and lists every link', () => {
    renderThemed(<NetworkTab network={network} />);
    expect(screen.getAllByTestId('network-edge')).toHaveLength(4);
    const links = screen.getByRole('table', { name: 'Links between accounts' });
    expect(within(links).getAllByRole('row').slice(1)).toHaveLength(5);
    expect(within(links).getByText(ABSENT)).toBeInTheDocument();
  });

  it('[FR-04-08] zooms with the buttons, within its limits, and resets', async () => {
    renderThemed(<NetworkTab network={network} />);
    const drawing = screen.getByRole('group', { name: "Drawing of the account's network" });
    const zoomIn = screen.getByRole('button', { name: 'Zoom in' });
    const zoomOut = screen.getByRole('button', { name: 'Zoom out' });
    expect(drawing).toHaveAttribute('viewBox', '-260 -260 520 520');

    await userEvent.click(zoomIn);
    expect(drawing).toHaveAttribute('data-zoom', String(ZOOM.step));
    while (!(zoomIn as HTMLButtonElement).disabled) await userEvent.click(zoomIn);
    expect(drawing).toHaveAttribute('data-zoom', String(ZOOM.max));

    await userEvent.click(screen.getByRole('button', { name: 'Reset the view' }));
    expect(drawing).toHaveAttribute('viewBox', '-260 -260 520 520');
    while (!(zoomOut as HTMLButtonElement).disabled) await userEvent.click(zoomOut);
    expect(drawing).toHaveAttribute('data-zoom', String(ZOOM.min));
    expect(drawing).toHaveAttribute('viewBox', '-520 -520 1040 1040');
  });

  it('[FR-04-08] pans by dragging, or by the arrow keys when focused', async () => {
    renderThemed(<NetworkTab network={network} />);
    const drawing = screen.getByRole('group', { name: "Drawing of the account's network" });
    vi.spyOn(drawing, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 520, 520));
    // jsdom does not implement pointer capture.
    Object.defineProperty(drawing, 'setPointerCapture', { value: () => undefined });

    // At zoom 1 a pixel is one unit: dragging 40 px right moves the view 40 units left.
    fireEvent.pointerDown(drawing, { clientX: 100, clientY: 100, pointerId: 1 });
    fireEvent.pointerMove(drawing, { clientX: 140, clientY: 120, pointerId: 1 });
    fireEvent.pointerUp(drawing, { pointerId: 1 });
    expect(drawing).toHaveAttribute('viewBox', '-300 -280 520 520');
    // Moving without a button held does nothing.
    fireEvent.pointerMove(drawing, { clientX: 300, clientY: 300, pointerId: 1 });
    expect(drawing).toHaveAttribute('viewBox', '-300 -280 520 520');
    // Nor does dragging with the right button.
    fireEvent.pointerDown(drawing, { clientX: 100, clientY: 100, pointerId: 2, button: 2 });
    fireEvent.pointerMove(drawing, { clientX: 200, clientY: 200, pointerId: 2 });
    expect(drawing).toHaveAttribute('viewBox', '-300 -280 520 520');

    await userEvent.click(screen.getByRole('button', { name: 'Reset the view' }));
    drawing.focus();
    await userEvent.keyboard('{ArrowRight}{ArrowDown}');
    expect(drawing).toHaveAttribute('viewBox', '-220 -220 520 520');
  });

  it("[FR-04-12] rings the focused drawing in the theme's focus colour, in dark mode too", () => {
    renderThemed(<NetworkTab network={network} />, 'dark');
    const drawing = screen.getByRole('group', { name: "Drawing of the account's network" });
    // jsdom does not apply :focus-visible; read the rule the drawing's class carries.
    const rules = [...document.querySelectorAll('style')]
      .map((style) => style.textContent)
      .join('');
    const classes = [...drawing.classList].filter((name) => /^(css|mui)-/.test(name));
    const rule = classes
      .flatMap((name) => rules.split('}').filter((r) => r.includes(`.${name}:focus-visible`)))
      .join('}');
    expect(rule.toLowerCase()).toContain(
      `outline:3px solid ${tokens.color.dark.focusRing.toLowerCase()}`,
    );
  });
});

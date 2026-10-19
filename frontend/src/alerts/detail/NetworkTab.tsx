import AddRounded from '@mui/icons-material/AddRounded';
import CenterFocusStrongRounded from '@mui/icons-material/CenterFocusStrongRounded';
import RemoveRounded from '@mui/icons-material/RemoveRounded';
import WarningRounded from '@mui/icons-material/WarningRounded';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import { useTheme } from '@mui/material/styles';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { AccountNetwork } from '../../api/generated/types.gen';

type Node = AccountNetwork['nodes'][number];

/** The rings' least radii, and the room a node needs along its ring (a 28-unit circle and a gap). */
const RING = { inner: 120, outer: 220, gap: 100 } as const;
const NODE_ROOM = 36;
/** The least half-width of the drawing at zoom 1, and the margin left outside the outer ring. */
const MIN_HALF = 260;
const MARGIN = 40;
export const ZOOM = { min: 0.5, max: 4, step: 1.5 } as const;
/** How far an arrow key pans, in the drawing's units at zoom 1. */
const PAN_STEP = 40;

export interface Placed extends Node {
  x: number;
  y: number;
}

export interface Layout {
  nodes: Placed[];
  /** Half the drawing's width at zoom 1, so the outer ring fits. */
  half: number;
}

/**
 * Places the account at the centre, its counterparties on a ring around it and the second degree
 * on an outer ring. Each counterparty gets a sector of the circle in proportion to the accounts
 * hanging off it, sits at the sector's centre, and its second-degree accounts are spread across
 * the sector, so each is drawn beside it; second-degree accounts linked to no counterparty share a
 * sector of their own. The rings grow with the number of sectors so no two nodes overlap. A fixed
 * layout, not a simulation: the same graph is always drawn the same way.
 */
export function layout(network: AccountNetwork): Layout {
  // The contract does not make tokens unique: a repeated token is drawn once, where it first
  // appears (the accounts table still lists every copy).
  const seen = new Set<string>();
  const nodes = network.nodes.filter((n) => !seen.has(n.token) && seen.add(n.token));
  const centre = nodes.filter((n) => n.degree === 0);
  const first = nodes.filter((n) => n.degree === 1);
  const linked = (a: string, b: string) =>
    network.edges.some(
      (e) => (e.source === a && e.target === b) || (e.source === b && e.target === a),
    );
  const groups: { parent: Node | null; children: Node[] }[] = first.map((parent) => ({
    parent,
    children: [],
  }));
  const orphans: Node[] = [];
  for (const node of nodes.filter((n) => n.degree === 2)) {
    const index = first.findIndex((p) => linked(p.token, node.token));
    (groups[index]?.children ?? orphans).push(node);
  }
  if (orphans.length > 0) groups.push({ parent: null, children: orphans });

  // A sector is as wide as the larger of its counterparty (1) and its accounts; the rings are
  // large enough that the narrowest slot on either still has room for a node.
  const weights = groups.map((g) => Math.max(1, g.children.length));
  const total = weights.reduce((sum, w) => sum + w, 0);
  const least = (NODE_ROOM * total) / (2 * Math.PI);
  const inner = Math.max(RING.inner, least);
  const outer = Math.max(RING.outer, inner + RING.gap, least);
  const at = (node: Node, radius: number, angle: number): Placed => ({
    ...node,
    x: radius * Math.cos(angle),
    y: radius * Math.sin(angle),
  });

  const placed: Placed[] = centre.map((node) => ({ ...node, x: 0, y: 0 }));
  // With no sectors (an account alone) the loop does not run; `from` is never used.
  let from = total === 0 ? 0 : -Math.PI / 2 - (Math.PI * (weights[0] ?? 0)) / total;
  groups.forEach((group, i) => {
    const width = (2 * Math.PI * (weights[i] ?? 0)) / total;
    const middle = from + width / 2;
    if (group.parent !== null) placed.push(at(group.parent, inner, middle));
    // Each child gets a node's room of the outer ring, centred on its counterparty, rather than an
    // even share of the sector, so a counterparty with a wide sector keeps its accounts close. The
    // outer ring is large enough that the room fits in every share (outer >= least).
    const step = NODE_ROOM / outer;
    group.children.forEach((child, j) => {
      placed.push(at(child, outer, middle + step * (j - (group.children.length - 1) / 2)));
    });
    from += width;
  });
  return { nodes: placed, half: Math.max(MIN_HALF, outer + MARGIN) };
}

/** The end of a token, enough to tell nodes apart on the drawing; the table has the whole token. */
function shortToken(token: string): string {
  return `…${token.slice(-4)}`;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/**
 * The account's transaction network (FR-04-08): the flagged account, its counterparties and their
 * counterparties, at most 50 accounts, with accounts confirmed as fraud in red with a glow, a
 * thicker ring and a warning mark, so colour is not the only cue. Zoom with the buttons; pan by
 * dragging or, with the drawing focused, the arrow keys.
 *
 * Drawn as SVG in a fixed layout, not with vis-network or D3 (ADR 0085). The drawing is a named
 * group described by the summary; the two tables below it carry every account and link.
 */
export function NetworkTab({ network }: { network: AccountNetwork }) {
  const { t } = useTranslation('alerts');
  const theme = useTheme();
  const { nodes: placed, half: extent } = layout(network);
  const at = new Map(placed.map((n) => [n.token, n]));
  const fraud = network.nodes.filter((n) => n.confirmed_fraud).length;
  const [view, setView] = useState({ zoom: 1, x: 0, y: 0 });
  const drag = useRef<{ x: number; y: number; id: number } | null>(null);
  const svg = useRef<SVGSVGElement>(null);
  const largest = Math.max(...network.edges.map((e) => e.transaction_count), 1);

  const half = extent / view.zoom;
  const zoomBy = (factor: number) => {
    setView((v) => ({ ...v, zoom: clamp(v.zoom * factor, ZOOM.min, ZOOM.max) }));
  };
  const panBy = (dx: number, dy: number) => {
    setView((v) => ({
      ...v,
      x: clamp(v.x + dx, -extent, extent),
      y: clamp(v.y + dy, -extent, extent),
    }));
  };

  const onPointerDown = (event: PointerEvent<SVGSVGElement>) => {
    // Only the main button drags; a right or middle click is left to the browser.
    if (event.button !== 0) return;
    drag.current = { x: event.clientX, y: event.clientY, id: event.pointerId };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const onPointerMove = (event: PointerEvent<SVGSVGElement>) => {
    const start = drag.current;
    if (start?.id !== event.pointerId) return;
    const width = svg.current?.getBoundingClientRect().width ?? 0;
    // Pixels to drawing units: the visible width is 2 * half units across `width` pixels.
    const scale = width > 0 ? (2 * half) / width : 1;
    drag.current = { ...start, x: event.clientX, y: event.clientY };
    panBy(-(event.clientX - start.x) * scale, -(event.clientY - start.y) * scale);
  };
  const onPointerUp = () => {
    drag.current = null;
  };
  const onKeyDown = (event: KeyboardEvent<SVGSVGElement>) => {
    const step = PAN_STEP / view.zoom;
    const move: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };
    const delta = move[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    panBy(...delta);
  };

  // D-33's measured pairs: the border on paper and on its tint (3:1), the text on the tint (4.5:1).
  const risk = theme.palette.risk.high;
  const fraudColour = risk.border;
  const degreeName = (degree: number) => t(`network.degree.${String(degree) as '0' | '1' | '2'}`);

  return (
    <Stack spacing={2}>
      <Typography id="fs-network-summary" variant="body2" color="text.secondary">
        {t('network.summary', { count: network.nodes.length, fraud })}
      </Typography>
      <Stack direction="row" spacing={1}>
        <IconButton
          aria-label={t('network.zoomIn')}
          onClick={() => {
            zoomBy(ZOOM.step);
          }}
          disabled={view.zoom >= ZOOM.max}
          sx={{ minWidth: 48, minHeight: 48 }}
        >
          <AddRounded />
        </IconButton>
        <IconButton
          aria-label={t('network.zoomOut')}
          onClick={() => {
            zoomBy(1 / ZOOM.step);
          }}
          disabled={view.zoom <= ZOOM.min}
          sx={{ minWidth: 48, minHeight: 48 }}
        >
          <RemoveRounded />
        </IconButton>
        <IconButton
          aria-label={t('network.reset')}
          onClick={() => {
            setView({ zoom: 1, x: 0, y: 0 });
          }}
          sx={{ minWidth: 48, minHeight: 48 }}
        >
          <CenterFocusStrongRounded />
        </IconButton>
      </Stack>
      <Box
        component="svg"
        ref={svg}
        // A focusable control that pans, not a static picture: a group, named and described.
        role="group"
        aria-label={t('network.drawing')}
        aria-describedby="fs-network-summary fs-network-help"
        tabIndex={0}
        viewBox={`${String(view.x - half)} ${String(view.y - half)} ${String(2 * half)} ${String(2 * half)}`}
        data-zoom={view.zoom}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onKeyDown={onKeyDown}
        sx={{
          width: '100%',
          aspectRatio: '1',
          border: 1,
          borderColor: 'divider',
          borderRadius: 1,
          bgcolor: 'background.paper',
          touchAction: 'none',
          cursor: 'grab',
          // The theme's focus ring (FR-04-12), the pair D-33 measures on paper in both modes.
          '&:focus-visible': { outline: `3px solid ${theme.palette.focusRing}`, outlineOffset: 2 },
        }}
      >
        <defs>
          <filter id="fs-network-glow" x="-100%" y="-100%" width="300%" height="300%">
            <feDropShadow dx="0" dy="0" stdDeviation="5" floodColor={fraudColour} />
          </filter>
        </defs>
        {network.edges.map((edge, index) => {
          const from = at.get(edge.source);
          const to = at.get(edge.target);
          if (from === undefined || to === undefined) return null;
          return (
            <line
              key={`${String(index)}:${edge.source}>${edge.target}`}
              data-testid="network-edge"
              x1={from.x}
              y1={from.y}
              x2={to.x}
              y2={to.y}
              stroke={theme.palette.text.secondary}
              strokeWidth={1 + 3 * (edge.transaction_count / largest)}
            />
          );
        })}
        {placed.map((node, index) => (
          <g
            key={`${String(index)}:${node.token}`}
            data-testid="network-node"
            data-token={node.token}
            data-fraud={node.confirmed_fraud}
            transform={`translate(${String(node.x)} ${String(node.y)})`}
          >
            <circle
              r={node.degree === 0 ? 18 : 14}
              fill={node.confirmed_fraud ? risk.bg : theme.palette.background.paper}
              stroke={node.confirmed_fraud ? fraudColour : theme.palette.text.primary}
              strokeWidth={node.confirmed_fraud ? 4 : node.degree === 0 ? 3 : 1.5}
              {...(node.confirmed_fraud ? { filter: 'url(#fs-network-glow)' } : {})}
            />
            {node.confirmed_fraud ? (
              <text
                textAnchor="middle"
                dominantBaseline="central"
                fontSize="16"
                fontWeight="700"
                fill={risk.text}
              >
                !
              </text>
            ) : null}
            <text
              y={node.degree === 0 ? 32 : 28}
              textAnchor="middle"
              fontSize="11"
              fill={theme.palette.text.primary}
            >
              {node.degree === 0 ? t('network.thisAccount') : shortToken(node.token)}
            </text>
          </g>
        ))}
      </Box>
      <Typography id="fs-network-help" variant="caption" color="text.secondary">
        {t('network.help')}
      </Typography>

      <Table size="small" aria-label={t('network.accounts')}>
        <TableHead>
          <TableRow>
            <TableCell component="th" scope="col">
              {t('network.account')}
            </TableCell>
            <TableCell component="th" scope="col">
              {t('network.degreeHeading')}
            </TableCell>
            <TableCell component="th" scope="col">
              {t('network.fraudHeading')}
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {network.nodes.map((node, index) => (
            <TableRow key={`${String(index)}:${node.token}`}>
              <TableCell
                component="th"
                scope="row"
                sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}
              >
                {node.token}
              </TableCell>
              <TableCell>{degreeName(node.degree)}</TableCell>
              <TableCell>
                {node.confirmed_fraud ? (
                  <Box
                    component="span"
                    sx={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 0.5,
                      color: 'risk.high.text',
                      fontWeight: 600,
                    }}
                  >
                    <WarningRounded fontSize="inherit" aria-hidden />
                    {t('network.confirmedFraud')}
                  </Box>
                ) : (
                  t('network.notFraud')
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <Table size="small" aria-label={t('network.links')}>
        <TableHead>
          <TableRow>
            <TableCell component="th" scope="col">
              {t('network.from')}
            </TableCell>
            <TableCell component="th" scope="col">
              {t('network.to')}
            </TableCell>
            <TableCell component="th" scope="col" align="right">
              {t('network.transactions')}
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {network.edges.map((edge, index) => (
            <TableRow key={`${String(index)}:${edge.source}>${edge.target}`}>
              <TableCell sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
                {edge.source}
              </TableCell>
              <TableCell sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
                {edge.target}
              </TableCell>
              <TableCell align="right">
                <Typography variant="numeric" component="span">
                  {edge.transaction_count}
                </Typography>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
}

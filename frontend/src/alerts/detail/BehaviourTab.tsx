import WarningRounded from '@mui/icons-material/WarningRounded';
import Box from '@mui/material/Box';
import { alpha } from '@mui/material/styles';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { BehaviourProfile } from '../../api/generated/types.gen';
import { ChannelChip } from '../../design-system/components/ChannelChip/ChannelChip';
import { Money } from '../../design-system/components/Money/Money';
import { useFormatLocale } from '../../region/formatLocale';

/** Read by a screen reader, not shown (the usual clip pattern; MUI's copy is not a dependency). */
const visuallyHidden = {
  position: 'absolute',
  width: '1px',
  height: '1px',
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
  margin: '-1px',
  padding: 0,
  border: 0,
} as const;

/** The hours in rows of six, so the 24 fit the drawer's width (00-05, 06-11, 12-17, 18-23). */
const HOUR_ROWS = [0, 6, 12, 18] as const;

function Panel({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <Box component="section" aria-labelledby={id}>
      <Typography id={id} variant="subtitle1" component="h3" sx={{ mb: 1 }}>
        {title}
      </Typography>
      {children}
    </Box>
  );
}

/** A bar to scale, repeating a number written beside it; hidden from assistive tech. */
function Bar({ share, testId }: { share: number; testId: string }) {
  return (
    <Box
      aria-hidden
      data-testid={testId}
      sx={{
        height: 12,
        borderRadius: 0.5,
        width: `${String(share * 100)}%`,
        // The accent, which D-33 measures on paper in both modes (at least 4.5:1).
        bgcolor: 'secondary.main',
      }}
    />
  );
}

/**
 * The account's behavioural fingerprint (FR-04-07) in four panels: transactions by hour of day,
 * by amount, by channel, and this transaction against the account's normal, with the unusual
 * fields marked in amber and by an icon and a word, so colour is not the only cue.
 *
 * Tables with shading and bars rather than a heatmap, histogram and pie chart from a chart library
 * (ADR 0085, which says what they do not provide): every number is written, and each panel reads
 * in order to a screen reader.
 */
export function BehaviourTab({ profile }: { profile: BehaviourProfile }) {
  const { t } = useTranslation('alerts');
  const locale = useFormatLocale();
  const count = new Intl.NumberFormat(locale);
  const percent = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0 });
  const hourly = profile.hourly_counts;
  const busiest = Math.max(...hourly, 1);
  const largestBin = Math.max(...profile.amount_histogram.map((bin) => bin.count), 1);
  const channelTotal = profile.channel_mix.reduce((sum, entry) => sum + entry.count, 0);
  const largestChannel = Math.max(...profile.channel_mix.map((entry) => entry.count), 1);
  const hour = (h: number) => String(h).padStart(2, '0');

  const shown = (value: string | number | null) => {
    if (value === null) return t('behaviour.none');
    return typeof value === 'number' ? count.format(value) : value;
  };

  return (
    <Stack spacing={3}>
      <Typography variant="body2" color="text.secondary">
        {t('behaviour.window', { days: profile.window_days })}
      </Typography>

      <Panel id="fs-behaviour-compare" title={t('behaviour.compare')}>
        {profile.comparison.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {t('behaviour.noComparison')}
          </Typography>
        ) : (
          <Table size="small" aria-labelledby="fs-behaviour-compare">
            <TableHead>
              <TableRow>
                <TableCell component="th" scope="col">
                  {t('behaviour.attribute')}
                </TableCell>
                <TableCell component="th" scope="col">
                  {t('behaviour.thisTransaction')}
                </TableCell>
                <TableCell component="th" scope="col">
                  {t('behaviour.normal')}
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {profile.comparison.map((row, index) => (
                <TableRow
                  // The contract does not make attributes unique; the position does.
                  key={`${String(index)}:${row.attribute}`}
                  data-anomalous={row.anomalous}
                  sx={
                    row.anomalous
                      ? (theme) => ({
                          bgcolor: theme.palette.risk.medium.bg,
                          '& td, & th': { color: theme.palette.risk.medium.text },
                        })
                      : undefined
                  }
                >
                  {/* The contract's attribute key; there is no plain-language name for it yet. */}
                  <TableCell component="th" scope="row" sx={{ fontFamily: 'monospace' }}>
                    {row.attribute}
                  </TableCell>
                  <TableCell>
                    {shown(row.this_transaction)}
                    {row.anomalous ? (
                      <Box
                        component="span"
                        sx={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: 0.5,
                          marginInlineStart: 1,
                        }}
                      >
                        <WarningRounded fontSize="inherit" aria-hidden />
                        <Typography variant="caption" component="span" sx={{ fontWeight: 600 }}>
                          {t('behaviour.unusual')}
                        </Typography>
                      </Box>
                    ) : null}
                  </TableCell>
                  <TableCell>{shown(row.normal)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Panel>

      <Panel id="fs-behaviour-hours" title={t('behaviour.hours')}>
        <Table size="small" aria-labelledby="fs-behaviour-hours" sx={{ tableLayout: 'fixed' }}>
          <TableBody>
            {HOUR_ROWS.map((start) => (
              <TableRow key={start}>
                {hourly.slice(start, start + 6).map((value, offset) => {
                  const h = start + offset;
                  return (
                    <TableCell key={h} align="center" sx={{ px: 0.5 }}>
                      <Box component="span" sx={visuallyHidden}>
                        {t('behaviour.hourCell', { hour: hour(h), count: value })}
                      </Box>
                      {/* The shade repeats the count; text stays on the plain background. */}
                      <Box
                        aria-hidden
                        data-testid="hour-shade"
                        sx={(theme) => ({
                          height: 16,
                          borderRadius: 0.5,
                          border: 1,
                          borderColor: 'divider',
                          bgcolor: alpha(theme.palette.secondary.main, value / busiest),
                        })}
                      />
                      <Typography variant="caption" component="div" aria-hidden>
                        {hour(h)}
                      </Typography>
                      <Typography variant="numeric" component="div" aria-hidden>
                        {count.format(value)}
                      </Typography>
                    </TableCell>
                  );
                })}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Panel>

      <Panel id="fs-behaviour-amounts" title={t('behaviour.amounts')}>
        {profile.amount_histogram.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {t('behaviour.empty')}
          </Typography>
        ) : (
          <Table size="small" aria-labelledby="fs-behaviour-amounts">
            <TableHead>
              <TableRow>
                <TableCell component="th" scope="col">
                  {t('behaviour.range')}
                </TableCell>
                <TableCell component="th" scope="col" align="right">
                  {t('behaviour.count')}
                </TableCell>
                <TableCell component="th" scope="col" aria-hidden sx={{ width: '35%' }} />
              </TableRow>
            </TableHead>
            <TableBody>
              {profile.amount_histogram.map((bin, index) => (
                <TableRow key={`${String(index)}:${bin.lower_rwf}-${bin.upper_rwf}`}>
                  <TableCell component="th" scope="row">
                    <Money amount={bin.lower_rwf} currency="RWF" /> –{' '}
                    <Money amount={bin.upper_rwf} currency="RWF" />
                  </TableCell>
                  <TableCell align="right">
                    <Typography variant="numeric" component="span">
                      {count.format(bin.count)}
                    </Typography>
                  </TableCell>
                  <TableCell aria-hidden>
                    <Bar share={bin.count / largestBin} testId="amount-bar" />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Panel>

      <Panel id="fs-behaviour-channels" title={t('behaviour.channels')}>
        {profile.channel_mix.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {t('behaviour.empty')}
          </Typography>
        ) : (
          <Table size="small" aria-labelledby="fs-behaviour-channels">
            <TableHead>
              <TableRow>
                <TableCell component="th" scope="col">
                  {t('behaviour.channel')}
                </TableCell>
                <TableCell component="th" scope="col" align="right">
                  {t('behaviour.count')}
                </TableCell>
                <TableCell component="th" scope="col" align="right">
                  {t('behaviour.share')}
                </TableCell>
                <TableCell component="th" scope="col" aria-hidden sx={{ width: '30%' }} />
              </TableRow>
            </TableHead>
            <TableBody>
              {profile.channel_mix.map((entry, index) => (
                <TableRow key={`${String(index)}:${entry.channel}`}>
                  <TableCell component="th" scope="row">
                    <ChannelChip channel={entry.channel} />
                  </TableCell>
                  <TableCell align="right">
                    <Typography variant="numeric" component="span">
                      {count.format(entry.count)}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Typography variant="numeric" component="span">
                      {percent.format(channelTotal === 0 ? 0 : entry.count / channelTotal)}
                    </Typography>
                  </TableCell>
                  <TableCell aria-hidden>
                    <Bar share={entry.count / largestChannel} testId="channel-bar" />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Panel>
    </Stack>
  );
}

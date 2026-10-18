import ArrowDownwardRounded from '@mui/icons-material/ArrowDownwardRounded';
import ArrowUpwardRounded from '@mui/icons-material/ArrowUpwardRounded';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Explanation, ShapContribution } from '../../api/generated/types.gen';
import { useFormatLocale } from '../../region/formatLocale';

/** D-45: the first view shows this many contributions; the rest are one summary row until asked. */
export const SHOWN_FIRST = { wide: 10, phone: 5 } as const;

/** The contributions, largest effect first (UX-DASH-05: sorted by |SHAP| descending). */
export function byEffect(contributions: readonly ShapContribution[]): ShapContribution[] {
  return [...contributions].sort((a, b) => Math.abs(b.shap) - Math.abs(a.shap));
}

/**
 * The SHAP explanation (FR-04-05, UX-DASH-05, D-45): every contribution in margin space, largest
 * first, each with its feature, raw value, signed effect and a bar to scale. Increases are drawn
 * in the high-risk colour and decreases in the low-risk one, with an arrow and a sign as well, so
 * colour is never the only cue. The base value and the final margin are stated above the table.
 *
 * A table, not a chart library: it reads in order for a screen reader, needs no new dependency
 * and no chunk of its own (D-39). The plain-language description per feature (D-45's templates)
 * does not exist yet; the feature's name and value stand in for it.
 */
export function ShapChart({ explanation, phone }: { explanation: Explanation; phone: boolean }) {
  const { t } = useTranslation('alerts');
  const locale = useFormatLocale();
  const [all, setAll] = useState(false);
  const sorted = byEffect(explanation.contributions);
  const first = phone ? SHOWN_FIRST.phone : SHOWN_FIRST.wide;
  const shown = all ? sorted : sorted.slice(0, first);
  const rest = sorted.slice(first);
  const largest = Math.max(...sorted.map((c) => Math.abs(c.shap)), Number.EPSILON);
  const signed = new Intl.NumberFormat(locale, {
    signDisplay: 'always',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const plain = new Intl.NumberFormat(locale, { maximumFractionDigits: 4 });
  const percent = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 });
  const net = rest.reduce((sum, c) => sum + c.shap, 0);

  const value = (v: ShapContribution['value']) => {
    if (v === null) return t('shap.missing');
    return typeof v === 'number' ? plain.format(v) : v;
  };

  return (
    <Box>
      <Typography variant="body2" sx={{ mb: 1 }}>
        {t('shap.annotation', {
          base: signed.format(explanation.base_value),
          final: signed.format(explanation.final_margin),
          probability: percent.format(explanation.calibrated_probability),
        })}
      </Typography>
      <Table size="small" aria-label={t('shap.caption')}>
        <TableHead>
          <TableRow>
            <TableCell component="th" scope="col">
              {t('shap.feature')}
            </TableCell>
            <TableCell component="th" scope="col">
              {t('shap.value')}
            </TableCell>
            <TableCell component="th" scope="col" align="right">
              {t('shap.effect')}
            </TableCell>
            <TableCell component="th" scope="col" sx={{ width: '35%' }}>
              <Box component="span" sx={visuallyHidden}>
                {t('shap.bar')}
              </Box>
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {shown.map((c) => {
            const increases = c.direction === 'INCREASES_RISK';
            return (
              <TableRow key={c.feature} data-direction={c.direction}>
                <TableCell component="th" scope="row" sx={{ fontFamily: 'monospace' }}>
                  {c.feature}
                </TableCell>
                <TableCell>{value(c.value)}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {increases ? (
                    <ArrowUpwardRounded fontSize="inherit" aria-hidden />
                  ) : (
                    <ArrowDownwardRounded fontSize="inherit" aria-hidden />
                  )}{' '}
                  <Typography variant="numeric" component="span">
                    {signed.format(c.shap)}
                  </Typography>
                </TableCell>
                <TableCell aria-hidden>
                  <Box
                    data-testid="shap-bar"
                    sx={(theme) => ({
                      height: 12,
                      borderRadius: 0.5,
                      width: `${String((Math.abs(c.shap) / largest) * 100)}%`,
                      bgcolor: theme.palette.risk[increases ? 'high' : 'low'].border,
                    })}
                  />
                </TableCell>
              </TableRow>
            );
          })}
          {all || rest.length === 0 ? null : (
            <TableRow>
              <TableCell component="th" scope="row" colSpan={2}>
                {t('shap.others', { count: rest.length })}
              </TableCell>
              <TableCell align="right">
                <Typography variant="numeric" component="span">
                  {signed.format(net)}
                </Typography>
              </TableCell>
              <TableCell />
            </TableRow>
          )}
        </TableBody>
      </Table>
      {rest.length === 0 ? null : (
        <Button
          onClick={() => {
            setAll(!all);
          }}
          aria-expanded={all}
          sx={{ mt: 1, minHeight: 48 }}
        >
          {all
            ? t('shap.showFirst', { count: first })
            : t('shap.showAll', { count: sorted.length })}
        </Button>
      )}
    </Box>
  );
}

const visuallyHidden = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const;

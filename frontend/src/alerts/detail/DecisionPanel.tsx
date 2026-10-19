import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { AlertDetail, AlertStatus, Role } from '../../api/generated/types.gen';
import { useSession } from '../../auth/session';
import { ApiError } from '../queries';
import { UNDO_GRACE_MS, useNotify } from '../undo';
import { codePoints, isLongEnough, TEXT_MAX, TEXT_MIN, useDecide, useEscalate } from './actions';

/** The statuses an alert can still be decided or escalated in (the server is the judge). */
export const DECIDABLE: ReadonlySet<AlertStatus> = new Set(['PENDING', 'IN_REVIEW', 'ESCALATED']);

/** E.8's ranking; an escalation goes only to a role strictly above the caller's. */
const RANK: Partial<Record<Role, number>> = { ANALYST: 0, SENIOR_ANALYST: 1, RISK_OFFICER: 2 };
const TARGETS = ['SENIOR_ANALYST', 'RISK_OFFICER'] as const;
type Target = (typeof TARGETS)[number];

export function escalationTargets(role: Role | undefined): Target[] {
  const mine = role === undefined ? undefined : RANK[role];
  if (mine === undefined) return [];
  // Strictly above the caller: none for a risk officer, whom escalateAlert does not admit.
  return TARGETS.filter((target) => (RANK[target] ?? 0) > mine);
}

type Operation = 'decide' | 'escalate';

/** What an analyst reads when the server refuses an action, by the Problem's type and status. */
export function refusal(
  error: ApiError,
  operation: Operation,
):
  | 'act.stale'
  | 'act.deadlinePassed'
  | 'act.levelRequired'
  | 'act.invalid'
  | 'act.invalidReason'
  | 'act.badTarget'
  | 'act.gone'
  | 'act.signedOut'
  | 'act.failed' {
  switch (error.type) {
    case 'urn:fraudshield:problem:stale-alert':
      return 'act.stale';
    case 'urn:fraudshield:problem:review-deadline-passed':
      return 'act.deadlinePassed';
    case 'urn:fraudshield:problem:escalation-level-required':
      return 'act.levelRequired';
    default:
      break;
  }
  if (error.status === 404) return 'act.gone';
  if (error.status === 401) return 'act.signedOut';
  if (error.status === 400 || error.status === 422) {
    // escalateAlert's 422 is the target role (escalation_target_not_higher), not the text.
    if (operation === 'decide') return 'act.invalid';
    return error.status === 422 ? 'act.badTarget' : 'act.invalidReason';
  }
  return 'act.failed';
}

/**
 * D-29's Idempotency-Key, one per logical action. An action is its content: the same decision
 * with the same comment on the same version is the same action, and a retry of it reuses its key
 * so the server replays rather than repeats it; anything different (the other button, another
 * word, a newer version) is another action with its own key. A definite refusal (4xx) ends the
 * action, so pressing again starts a new one; a failure with no answer does not, since the first
 * attempt may have landed.
 */
function useActionKeys() {
  const keys = useRef(new Map<string, string>());
  return {
    keyFor(action: unknown): string {
      const id = JSON.stringify(action);
      let key = keys.current.get(id);
      if (key === undefined) {
        key = crypto.randomUUID();
        keys.current.set(id, key);
      }
      return key;
    },
    forget(action: unknown): void {
      keys.current.delete(JSON.stringify(action));
    },
  };
}

/**
 * The drawer's action panel (FR-04-04, FR-04-10, UX-DASH-06, UX-ROLE-AN-03): a comment of at least
 * 10 characters, then CONFIRM FRAUD or MARK LEGITIMATE; or a reason and a role to escalate to. A
 * decision closes the drawer, leaves the feed and offers Undo for its grace (D-44). Every action
 * carries the alert's version and an Idempotency-Key, so a changed alert or a repeated press is
 * refused or replayed by the server rather than applied twice (D-29).
 *
 * Built against mocks of decideAlert, undoAlertDecision and escalateAlert, which only M8B
 * implements.
 */
export function DecisionPanel({ alert, onDone }: { alert: AlertDetail; onDone: () => void }) {
  const { t } = useTranslation('alerts');
  const { user } = useSession();
  const notify = useNotify();
  const decide = useDecide(alert.alert_id);
  const escalate = useEscalate(alert.alert_id);
  const [comment, setComment] = useState('');
  const [reason, setReason] = useState('');
  const targets = escalationTargets(user?.role);
  const [target, setTarget] = useState<Target | ''>('');
  const keys = useActionKeys();
  // The latest refusal, whichever action it came from.
  const [failure, setFailure] = useState<{ error: ApiError; operation: Operation } | null>(null);

  if (!DECIDABLE.has(alert.status)) {
    return (
      <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
        {t('act.closed', { status: t(`status.${alert.status}`) })}
      </Typography>
    );
  }

  const busy = decide.isPending || escalate.isPending;
  const refused = (error: ApiError) =>
    error.status !== undefined && error.status >= 400 && error.status < 500;
  const commentOk = isLongEnough(comment);

  const send = (decision: 'CONFIRM_FRAUD' | 'MARK_LEGITIMATE') => {
    const body = { decision, comment: comment.trim(), alert_version: alert.version };
    // D-44's grace runs from when the decision was sent, whatever the server's clock says.
    const sentAt = Date.now();
    setFailure(null);
    decide.mutate(
      { body, idempotencyKey: keys.keyFor(body) },
      {
        onSuccess: (accepted) => {
          notify({
            message: t(decision === 'CONFIRM_FRAUD' ? 'act.confirmed' : 'act.legitimate', {
              name: alert.masked_account_name,
            }),
            undo: {
              alertId: alert.alert_id,
              decisionId: accepted.decision_id,
              deadline: Math.min(Date.parse(accepted.undo_until), sentAt + UNDO_GRACE_MS),
            },
          });
          onDone();
        },
        onError: (error) => {
          setFailure({ error, operation: 'decide' });
          if (refused(error)) keys.forget(body);
        },
      },
    );
  };

  const sendEscalation = () => {
    if (target === '') return;
    const body = { target_role: target, reason: reason.trim(), alert_version: alert.version };
    setFailure(null);
    escalate.mutate(
      { body, idempotencyKey: keys.keyFor(body) },
      {
        onSuccess: () => {
          notify({
            message: t('act.escalated', {
              name: alert.masked_account_name,
              role: t(`act.role.${target}`),
            }),
          });
          onDone();
        },
        onError: (error) => {
          setFailure({ error, operation: 'escalate' });
          if (refused(error)) keys.forget(body);
        },
      },
    );
  };

  return (
    <Box
      component="section"
      aria-labelledby="fs-act-heading"
      sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}
    >
      <Typography id="fs-act-heading" variant="subtitle1" component="h3" sx={{ mb: 1 }}>
        {t('act.heading')}
      </Typography>
      {failure === null ? null : (
        <Alert severity="error" sx={{ mb: 2 }}>
          {t(refusal(failure.error, failure.operation))}
        </Alert>
      )}
      <TextField
        label={t('act.comment')}
        value={comment}
        onChange={(event) => {
          setComment(event.target.value);
        }}
        multiline
        minRows={2}
        fullWidth
        required
        helperText={t('act.count', { count: codePoints(comment), min: TEXT_MIN, max: TEXT_MAX })}
        // A code point is at most two UTF-16 units, so this never cuts a comment the contract allows.
        slotProps={{ htmlInput: { maxLength: TEXT_MAX * 2 } }}
      />
      <Stack direction="row" spacing={1} useFlexGap sx={{ mt: 1.5, flexWrap: 'wrap' }}>
        <Button
          variant="outlined"
          color="error"
          disabled={!commentOk || busy}
          onClick={() => {
            send('CONFIRM_FRAUD');
          }}
          sx={{ minHeight: 48, flexGrow: 1 }}
        >
          {t('act.confirm')}
        </Button>
        <Button
          variant="contained"
          color="success"
          disabled={!commentOk || busy}
          onClick={() => {
            send('MARK_LEGITIMATE');
          }}
          sx={{ minHeight: 48, flexGrow: 1 }}
        >
          {t('act.markLegitimate')}
        </Button>
      </Stack>
      {busy ? (
        <Stack role="status" direction="row" spacing={1} sx={{ mt: 1, alignItems: 'center' }}>
          <CircularProgress size={20} aria-hidden />
          <Typography variant="body2">{t('act.sending')}</Typography>
        </Stack>
      ) : null}

      {targets.length === 0 ? null : (
        <Box sx={{ mt: 3 }}>
          <Typography variant="subtitle2" component="h4" sx={{ mb: 1 }}>
            {t('act.escalateHeading')}
          </Typography>
          <Stack spacing={1.5}>
            <TextField
              select
              label={t('act.target')}
              value={target}
              onChange={(event) => {
                setTarget(event.target.value as Target | '');
              }}
              slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
            >
              <option value="">{t('act.chooseTarget')}</option>
              {targets.map((role) => (
                <option key={role} value={role}>
                  {t(`act.role.${role}`)}
                </option>
              ))}
            </TextField>
            <TextField
              label={t('act.reason')}
              value={reason}
              onChange={(event) => {
                setReason(event.target.value);
              }}
              multiline
              minRows={2}
              fullWidth
              required
              helperText={t('act.count', {
                count: codePoints(reason),
                min: TEXT_MIN,
                max: TEXT_MAX,
              })}
            />
            <Button
              variant="outlined"
              disabled={target === '' || !isLongEnough(reason) || busy}
              onClick={sendEscalation}
              sx={{ minHeight: 48, alignSelf: 'flex-start' }}
            >
              {t('act.escalate')}
            </Button>
          </Stack>
        </Box>
      )}
    </Box>
  );
}

import Button from '@mui/material/Button';
import Snackbar from '@mui/material/Snackbar';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useTranslation } from 'react-i18next';
import { useUndo } from './detail/actions';

/** D-44's grace: a decision can be undone for 5 seconds after it is sent. */
export const UNDO_GRACE_MS = 5_000;
/** How long a notice stays readable, whether or not it can still be undone. */
const NOTICE_MS = 6_000;

interface Notice {
  message: string;
  /**
   * Present while the decision behind the notice can be undone: until `deadline`, which the
   * caller sets to the earlier of the server's undo_until and 5 s after it sent the decision.
   */
  undo?: { alertId: string; decisionId: string; deadline: number };
}

const NoticeContext = createContext<(notice: Notice) => void>(() => undefined);

/** Tells the analyst what just happened, with an Undo while the decision is still in its grace. */
export function useNotify(): (notice: Notice) => void {
  return useContext(NoticeContext);
}

/**
 * The feed's notices (FR-04-04's undo Snackbar, D-44). They live with the feed, not the drawer, so
 * a notice outlives the drawer that a decision closes.
 *
 * The Undo is offered only until its deadline, on a timer of its own, so hovering over the notice
 * (which pauses MUI's auto-hide) cannot stretch it. Ctrl+Z or ⌘Z undoes from anywhere while it is
 * offered, and the message says so, since by Tab a keyboard user may not reach the notice in 5 s
 * (WCAG 2.1.1). The 5 s itself is the server's grace (D-44), not the console's to extend.
 */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation('alerts');
  const [notice, setNotice] = useState<(Notice & { key: number }) | null>(null);
  const [undoOpen, setUndoOpen] = useState(false);
  const undo = useUndo();

  const notify = useCallback((next: Notice) => {
    setNotice({ ...next, key: Date.now() });
    setUndoOpen(next.undo !== undefined && next.undo.deadline > Date.now());
  }, []);

  // Withdraw the Undo at its deadline, whatever the Snackbar is doing.
  const deadline = notice?.undo?.deadline;
  useEffect(() => {
    if (!undoOpen || deadline === undefined) return undefined;
    const id = setTimeout(
      () => {
        setUndoOpen(false);
      },
      Math.max(0, deadline - Date.now()),
    );
    return () => {
      clearTimeout(id);
    };
  }, [undoOpen, deadline]);

  const undoNow = useCallback(() => {
    if (notice?.undo === undefined || !undoOpen || undo.isPending) return;
    const pending = notice.undo;
    undo.mutate(
      { alertId: pending.alertId, decisionId: pending.decisionId },
      {
        onSuccess: () => {
          notify({ message: t('act.undone') });
        },
        onError: (error) => {
          if (error.status === undefined) {
            // No answer: the Undo may not have reached the server; offer it again while it lasts.
            notify({ message: t('act.undoRetry'), undo: pending });
          } else if (error.type === 'urn:fraudshield:problem:undo-window-expired') {
            notify({ message: t('act.undoTooLate') });
          } else if (error.type === 'urn:fraudshield:problem:not-decision-author') {
            notify({ message: t('act.undoNotAuthor') });
          } else {
            notify({ message: t('act.undoFailed') });
          }
        },
      },
    );
  }, [notice, undoOpen, undo, notify, t]);

  useEffect(() => {
    if (!undoOpen) return undefined;
    const onKey = (event: KeyboardEvent) => {
      // In a text field, Ctrl+Z stays the field's own undo.
      const target = event.target as HTMLElement | null;
      if (
        target?.isContentEditable === true ||
        /^(INPUT|TEXTAREA|SELECT)$/.test(target?.tagName ?? '')
      ) {
        return;
      }
      if ((event.ctrlKey || event.metaKey) && !event.shiftKey && event.key.toLowerCase() === 'z') {
        event.preventDefault();
        undoNow();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, [undoOpen, undoNow]);

  return (
    <NoticeContext.Provider value={useMemo(() => notify, [notify])}>
      {children}
      <Snackbar
        key={notice?.key}
        open={notice !== null}
        message={
          notice === null
            ? undefined
            : undoOpen
              ? `${notice.message} ${t('act.undoHint')}`
              : notice.message
        }
        autoHideDuration={NOTICE_MS}
        onClose={(_event, reason) => {
          if (reason !== 'clickaway') setNotice(null);
        }}
        action={
          undoOpen ? (
            <Button
              color="inherit"
              onClick={undoNow}
              disabled={undo.isPending}
              sx={{ minHeight: 48 }}
            >
              {t('act.undo')}
            </Button>
          ) : undefined
        }
      />
    </NoticeContext.Provider>
  );
}

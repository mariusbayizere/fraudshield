import Button from '@mui/material/Button';
import Snackbar from '@mui/material/Snackbar';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useUndo } from './detail/actions';
import { ApiError } from './queries';

/** D-44's grace: a decision can be undone for 5 seconds. */
export const UNDO_GRACE_MS = 5_000;

interface Notice {
  message: string;
  /** Present while the decision behind the notice can still be undone. */
  undo?: { alertId: string; decisionId: string; until: number };
}

const NoticeContext = createContext<(notice: Notice) => void>(() => undefined);

/** Tells the analyst what just happened, with an Undo while the decision is still in its grace. */
export function useNotify(): (notice: Notice) => void {
  return useContext(NoticeContext);
}

/**
 * The feed's notices (FR-04-04's undo Snackbar, D-44). They live with the feed, not the drawer, so
 * a notice outlives the drawer that a decision closes. The Undo is offered only until the
 * server's `undo_until`, and never for longer than the 5 s grace, so a clock that runs behind the
 * server's cannot offer an Undo the server will refuse.
 */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation('alerts');
  const [notice, setNotice] = useState<(Notice & { key: number; hideAfter: number }) | null>(null);
  const undo = useUndo();

  const notify = useCallback((next: Notice) => {
    const now = Date.now();
    const hideAfter =
      next.undo === undefined ? 6_000 : Math.max(0, Math.min(next.undo.until - now, UNDO_GRACE_MS));
    setNotice({ ...next, key: now, hideAfter });
  }, []);

  const undoNow = () => {
    if (notice?.undo === undefined) return;
    const { alertId, decisionId } = notice.undo;
    undo.mutate(
      { alertId, decisionId },
      {
        onSuccess: () => {
          notify({ message: t('act.undone') });
        },
        onError: (error) => {
          notify({
            message:
              error instanceof ApiError &&
              error.type === 'urn:fraudshield:problem:undo-window-expired'
                ? t('act.undoTooLate')
                : t('act.undoFailed'),
          });
        },
      },
    );
  };

  return (
    <NoticeContext.Provider value={useMemo(() => notify, [notify])}>
      {children}
      <Snackbar
        key={notice?.key}
        open={notice !== null}
        message={notice?.message}
        autoHideDuration={notice?.hideAfter ?? null}
        onClose={(_event, reason) => {
          if (reason !== 'clickaway') setNotice(null);
        }}
        action={
          notice?.undo === undefined ? undefined : (
            <Button
              color="inherit"
              onClick={undoNow}
              disabled={undo.isPending}
              sx={{ minHeight: 48 }}
            >
              {t('act.undo')}
            </Button>
          )
        }
      />
    </NoticeContext.Provider>
  );
}

import MenuRounded from '@mui/icons-material/MenuRounded';
import NotificationsActiveOutlined from '@mui/icons-material/NotificationsActiveOutlined';
import SearchRounded from '@mui/icons-material/SearchRounded';
import TimelineRounded from '@mui/icons-material/TimelineRounded';
import AppBar from '@mui/material/AppBar';
import BottomNavigation from '@mui/material/BottomNavigation';
import BottomNavigationAction from '@mui/material/BottomNavigationAction';
import Box from '@mui/material/Box';
import Drawer from '@mui/material/Drawer';
import IconButton from '@mui/material/IconButton';
import Link from '@mui/material/Link';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import ListSubheader from '@mui/material/ListSubheader';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import useMediaQuery from '@mui/material/useMediaQuery';
import { Link as RouterLink, Outlet, useRouterState } from '@tanstack/react-router';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { tokens } from '../design-system/tokens';
import { LanguageSwitcher } from './LanguageSwitcher';
import { SECTIONS, type SectionGroup } from './sections';

const DRAWER_WIDTH = 240;
const GROUPS: readonly SectionGroup[] = ['analyst', 'riskOfficer', 'admin', 'settings'];
const PRIMARY_ICON: Record<string, ReactNode> = {
  '/alerts': <NotificationsActiveOutlined aria-hidden />,
  '/anomalies': <TimelineRounded aria-hidden />,
  '/search': <SearchRounded aria-hidden />,
};

function Navigation({ onNavigate }: { onNavigate?: () => void }) {
  const { t } = useTranslation();
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  return (
    <Box component="nav" aria-label={t('shell.navigation')}>
      {GROUPS.map((group) => (
        <List
          key={group}
          dense
          subheader={<ListSubheader component="h2">{t(`shell.group.${group}`)}</ListSubheader>}
        >
          {SECTIONS.filter((s) => s.group === group).map((section) => {
            const current = pathname === section.path || pathname.startsWith(`${section.path}/`);
            return (
              <ListItemButton
                key={section.path}
                component={RouterLink}
                to={section.path}
                selected={current}
                aria-current={current ? 'page' : undefined}
                onClick={onNavigate}
                sx={{ minHeight: tokens.touchTargetPx }}
              >
                <ListItemText primary={t(section.title)} />
              </ListItemButton>
            );
          })}
        </List>
      ))}
    </Box>
  );
}

/**
 * The staff console's frame (E.9, 05B A.7): a permanent navigation drawer on wide screens, a
 * bottom bar with the analyst's main destinations on phones, a skip link, and the page in <main>.
 * The drawer is anchored to the inline start, so right-to-left puts it on the right.
 */
export function AppShell() {
  const { t, i18n } = useTranslation();
  const wide = useMediaQuery((theme) => theme.breakpoints.up('md'), { noSsr: true });
  const [menuOpen, setMenuOpen] = useState(false);
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const primary = SECTIONS.filter((s) => s.primary === true);

  useEffect(() => {
    document.documentElement.lang = i18n.resolvedLanguage ?? i18n.language;
  }, [i18n.resolvedLanguage, i18n.language]);

  return (
    <Box sx={{ display: 'flex', minHeight: '100dvh' }}>
      <Link
        href="#main"
        sx={{
          position: 'absolute',
          insetInlineStart: 8,
          top: -48,
          zIndex: tokens.zIndex.tooltip,
          bgcolor: 'background.paper',
          p: 1,
          '&:focus': { top: 8 },
        }}
      >
        {t('shell.skipToContent')}
      </Link>
      <AppBar position="fixed" sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}>
        <Toolbar sx={{ gap: 2 }}>
          {wide ? null : (
            <IconButton
              color="inherit"
              edge="start"
              aria-label={t('shell.openMenu')}
              onClick={() => {
                setMenuOpen(true);
              }}
              sx={{ minWidth: tokens.touchTargetPx, minHeight: tokens.touchTargetPx }}
            >
              <MenuRounded aria-hidden />
            </IconButton>
          )}
          <Typography variant="h6" component="p" sx={{ flexGrow: 1 }}>
            {t('app.name')}
          </Typography>
          <Box sx={{ bgcolor: 'background.paper', borderRadius: 1 }}>
            <LanguageSwitcher />
          </Box>
        </Toolbar>
      </AppBar>
      {wide ? (
        <Drawer
          variant="permanent"
          anchor="left"
          sx={{ width: DRAWER_WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: DRAWER_WIDTH } }}
        >
          <Toolbar />
          <Navigation />
        </Drawer>
      ) : (
        <Drawer
          variant="temporary"
          anchor="left"
          open={menuOpen}
          onClose={() => {
            setMenuOpen(false);
          }}
          sx={{ '& .MuiDrawer-paper': { width: DRAWER_WIDTH } }}
        >
          <Navigation
            onNavigate={() => {
              setMenuOpen(false);
            }}
          />
        </Drawer>
      )}
      <Box
        component="main"
        id="main"
        tabIndex={-1}
        sx={{ flexGrow: 1, minWidth: 0, p: { xs: 2, md: 3 }, pb: wide ? 3 : 10 }}
      >
        <Toolbar />
        <Outlet />
      </Box>
      {wide ? null : (
        <BottomNavigation
          component="nav"
          aria-label={t('shell.primaryNavigation')}
          showLabels
          value={primary.find((s) => pathname.startsWith(s.path))?.path ?? false}
          sx={{
            position: 'fixed',
            bottom: 0,
            insetInline: 0,
            zIndex: (theme) => theme.zIndex.appBar,
          }}
        >
          {primary.map((section) => (
            <BottomNavigationAction
              key={section.path}
              component={RouterLink}
              to={section.path}
              value={section.path}
              label={t(section.title)}
              icon={PRIMARY_ICON[section.path]}
            />
          ))}
        </BottomNavigation>
      )}
    </Box>
  );
}

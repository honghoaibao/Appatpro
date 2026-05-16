import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:go_router/go_router.dart';
import 'screens/dashboard_screen.dart';
import 'screens/accounts_screen.dart';
import 'screens/config_screen.dart';
import 'screens/log_screen.dart';
import 'screens/stats_screen.dart';
import 'screens/multi_device/multi_device_screen.dart';
import 'screens/schedule_screen.dart';
import 'screens/export_screen.dart';
import 'screens/ws_monitor_screen.dart';
import 'screens/setup/permission_screen.dart';
import 'screens/splash_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor:           Colors.transparent,
    statusBarIconBrightness:  Brightness.light,
    systemNavigationBarColor: Color(0xFF0D0D14),
    systemNavigationBarIconBrightness: Brightness.light,
  ));
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp, DeviceOrientation.portraitDown,
  ]);
  runApp(const ProviderScope(child: AtProApp()));
}

final _router = GoRouter(
  initialLocation: '/splash',
  routes: [
    GoRoute(path: '/splash', builder: (c, s) => const SplashScreen()),
    GoRoute(path: '/setup', builder: (c, s) => const PermissionScreen()),
    ShellRoute(
      builder: (ctx, state, child) => AppShell(child: child),
      routes: [
        GoRoute(path: '/',          builder: (c, s) => const DashboardScreen()),
        GoRoute(path: '/accounts',  builder: (c, s) => const AccountsScreen()),
        GoRoute(path: '/stats',     builder: (c, s) => const StatsScreen()),
        GoRoute(path: '/logs',      builder: (c, s) => const LogScreen()),
        GoRoute(path: '/devices',   builder: (c, s) => const MultiDeviceScreen()),
        GoRoute(path: '/schedules', builder: (c, s) => const ScheduleScreen()),
        GoRoute(path: '/export',    builder: (c, s) => const ExportScreen()),
        GoRoute(path: '/ws',        builder: (c, s) => const WsMonitorScreen()),
        GoRoute(path: '/config',    builder: (c, s) => const ConfigScreen()),
      ],
    ),
  ],
);

class AtProApp extends StatelessWidget {
  const AtProApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp.router(
    title:                      'AT PRO',
    debugShowCheckedModeBanner: false,
    routerConfig:               _router,
    theme: ThemeData(
      useMaterial3:  true,
      colorScheme:   ColorScheme.fromSeed(seedColor: const Color(0xFF6C63FF), brightness: Brightness.dark),
      textTheme:     GoogleFonts.interTextTheme(ThemeData.dark().textTheme),
      scaffoldBackgroundColor: const Color(0xFF0D0D14),
      cardTheme: CardTheme(
        color: const Color(0xFF1A1A2E), elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
      appBarTheme: const AppBarTheme(backgroundColor: Color(0xFF0D0D14), elevation: 0, centerTitle: false),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: const Color(0xFF1A1A2E),
        indicatorColor:  const Color(0xFF6C63FF).withOpacity(0.2),
        elevation: 0,
      ),
    ),
  );
}

class AppShell extends StatelessWidget {
  final Widget child;
  const AppShell({super.key, required this.child});

  static const _tabs = [
    (icon: Icons.dashboard_rounded,  label: 'Trang chủ',     path: '/'),
    (icon: Icons.people_rounded,     label: 'Tài khoản', path: '/accounts'),
    (icon: Icons.bar_chart_rounded,  label: 'Thống kê',    path: '/stats'),
    (icon: Icons.devices_rounded,    label: 'Thiết bị',  path: '/devices'),
    (icon: Icons.settings_rounded,   label: 'Cài đặt',   path: '/config'),
  ];

  @override
  Widget build(BuildContext context) {
    final loc = GoRouterState.of(context).uri.path;
    int idx = _tabs.indexWhere((t) => t.path == loc);
    if (idx < 0) idx = 0;
    return Scaffold(
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: idx,
        onDestinationSelected: (i) => context.go(_tabs[i].path),
        destinations: _tabs.map((t) => NavigationDestination(
          icon: Icon(t.icon), label: t.label,
        )).toList(),
      ),
    );
  }
}

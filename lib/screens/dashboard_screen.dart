import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../services/providers.dart';

// ════════════════════════════════════════════════════════════════
//  Dashboard — gọn, tập trung vào 1 hành động chính: Farm
//  Chia 2 trạng thái rõ ràng: Idle / Farming
// ════════════════════════════════════════════════════════════════

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final farm      = ref.watch(farmProvider);
    final svcPoll   = ref.watch(serviceStatusProvider);
    final svcMap    = svcPoll.valueOrNull ?? {};
    final connected = svcMap['connected'] == true || svcMap['status'] == 'connected';

    return Scaffold(
      backgroundColor: const Color(0xFF0D0D14),
      body: SafeArea(
        child: farm.isRunning
            ? _FarmingView(farm: farm, ref: ref)
            : _IdleView(connected: connected, ref: ref),
      ),
    );
  }
}

// ── Idle: dịch vụ chưa chạy ──────────────────────────────────

class _IdleView extends ConsumerWidget {
  final bool connected;
  final WidgetRef ref;
  const _IdleView({required this.connected, required this.ref});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final accounts = ref.watch(accountsProvider).valueOrNull ?? [];
    final active   = accounts.where((a) => a['status'] == 'active').length;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        children: [
          const SizedBox(height: 32),

          // Header
          Row(children: [
            _StatusDot(online: connected),
            const SizedBox(width: 8),
            Text(
              connected ? 'Sẵn sàng' : 'Chưa bật dịch vụ',
              style: TextStyle(
                color:      connected ? const Color(0xFF10B981) : const Color(0xFF6B7280),
                fontSize:   13,
                fontWeight: FontWeight.w500,
              ),
            ),
            const Spacer(),
            const _AppLogo(),
          ]),

          const Spacer(),

          // Số tài khoản sẵn sàng
          Text('$active', style: const TextStyle(
            fontSize: 72, fontWeight: FontWeight.w800,
            color: Colors.white, height: 1,
          )),
          const SizedBox(height: 6),
          Text(
            active == 0 ? 'tài khoản' : 'tài khoản hoạt động',
            style: const TextStyle(color: Color(0xFF6B7280), fontSize: 15),
          ),

          const SizedBox(height: 48),

          // Nút bắt đầu
          _StartButton(
            enabled:  connected && active > 0,
            accounts: accounts
                .where((a) => a['status'] == 'active')
                .map((a) => a['username'] as String)
                .toList(),
            ref:      ref,
            hint: !connected
                ? 'Bật Accessibility Service trước'
                : active == 0
                    ? 'Thêm tài khoản để bắt đầu'
                    : null,
          ),

          const Spacer(),

          // Shortcuts
          _ShortcutRow(),

          const SizedBox(height: 24),
        ],
      ),
    );
  }
}

// ── Farming: đang chạy ───────────────────────────────────────

class _FarmingView extends StatelessWidget {
  final FarmState farm;
  final WidgetRef ref;
  const _FarmingView({required this.farm, required this.ref});

  @override
  Widget build(BuildContext context) {
    final stats   = ref.watch(liveStatsProvider).valueOrNull;
    final notifier = ref.read(farmProvider.notifier);

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        children: [
          const SizedBox(height: 32),

          // Header với nút dừng
          Row(children: [
            _PulseDot(),
            const SizedBox(width: 8),
            Text(
              farm.isPaused ? 'Đang tạm dừng' : 'Đang farm',
              style: TextStyle(
                color:      farm.isPaused ? const Color(0xFFF59E0B) : const Color(0xFF10B981),
                fontWeight: FontWeight.w600,
                fontSize:   14,
              ),
            ),
            const Spacer(),
            _StopButton(onStop: notifier.stop),
          ]),

          const Spacer(),

          // Account hiện tại
          _CurrentAccountBig(farm: farm),

          const SizedBox(height: 32),

          // Stats 2×2
          if (stats != null) _StatsGrid(stats: stats),

          const Spacer(),

          // Pause / Resume
          _PauseResumeButton(isPaused: farm.isPaused, notifier: notifier),

          const SizedBox(height: 32),
        ],
      ),
    );
  }
}

// ── Widgets ───────────────────────────────────────────────────

class _AppLogo extends StatelessWidget {
  const _AppLogo();
  @override
  Widget build(BuildContext context) => Container(
    width: 32, height: 32,
    decoration: BoxDecoration(
      gradient: const LinearGradient(
        colors: [Color(0xFF6C63FF), Color(0xFFEC4899)],
        begin: Alignment.topLeft, end: Alignment.bottomRight,
      ),
      borderRadius: BorderRadius.circular(8),
    ),
    child: const Center(child: Text('AT',
        style: TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 11))),
  );
}

class _StatusDot extends StatelessWidget {
  final bool online;
  const _StatusDot({required this.online});
  @override
  Widget build(BuildContext context) => Container(
    width: 8, height: 8,
    decoration: BoxDecoration(
      color:  online ? const Color(0xFF10B981) : const Color(0xFF6B7280),
      shape:  BoxShape.circle,
      boxShadow: online ? [BoxShadow(
        color: const Color(0xFF10B981).withOpacity(0.5), blurRadius: 6)] : null,
    ),
  );
}

class _PulseDot extends StatefulWidget {
  @override
  State<_PulseDot> createState() => _PulseDotState();
}
class _PulseDotState extends State<_PulseDot> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(seconds: 1))..repeat(reverse: true);
  late final Animation<double> _a = Tween(begin: 0.4, end: 1.0).animate(_c);
  @override void dispose() { _c.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => FadeTransition(
    opacity: _a,
    child: Container(width: 8, height: 8,
        decoration: const BoxDecoration(color: Color(0xFF10B981), shape: BoxShape.circle)),
  );
}

class _StartButton extends StatelessWidget {
  final bool enabled;
  final List<String> accounts;
  final WidgetRef ref;
  final String? hint;
  const _StartButton({required this.enabled, required this.accounts,
      required this.ref, this.hint});

  @override
  Widget build(BuildContext context) => Column(children: [
    SizedBox(
      width: double.infinity, height: 56,
      child: FilledButton(
        onPressed: enabled
            ? () => ref.read(farmProvider.notifier).start(accounts)
            : null,
        style: FilledButton.styleFrom(
          backgroundColor: const Color(0xFF6C63FF),
          disabledBackgroundColor: const Color(0xFF374151),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        ),
        child: const Text('Bắt đầu farm',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
      ),
    ),
    if (hint != null) ...[
      const SizedBox(height: 10),
      Text(hint!, style: const TextStyle(color: Color(0xFF6B7280), fontSize: 12)),
    ],
  ]);
}

class _StopButton extends StatelessWidget {
  final VoidCallback onStop;
  const _StopButton({required this.onStop});
  @override
  Widget build(BuildContext context) => TextButton(
    onPressed: onStop,
    style: TextButton.styleFrom(foregroundColor: const Color(0xFFEF4444)),
    child: const Text('Dừng', style: TextStyle(fontWeight: FontWeight.w600)),
  );
}

class _CurrentAccountBig extends StatelessWidget {
  final FarmState farm;
  const _CurrentAccountBig({required this.farm});
  @override
  Widget build(BuildContext context) => Column(children: [
    Text('@${farm.currentAccount ?? '...'}',
        style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w700, color: Colors.white)),
    const SizedBox(height: 8),
    Row(mainAxisAlignment: MainAxisAlignment.center, children: [
      Text('${farm.currentIndex}', style: const TextStyle(
          color: Color(0xFF6C63FF), fontWeight: FontWeight.w700, fontSize: 15)),
      const Text(' / ', style: TextStyle(color: Color(0xFF6B7280), fontSize: 15)),
      Text('${farm.totalAccounts}', style: const TextStyle(color: Color(0xFF6B7280), fontSize: 15)),
    ]),
    const SizedBox(height: 16),
    ClipRRect(
      borderRadius: BorderRadius.circular(4),
      child: LinearProgressIndicator(
        value:           farm.totalAccounts > 0 ? farm.currentIndex / farm.totalAccounts : 0,
        backgroundColor: const Color(0xFF374151),
        color:           const Color(0xFF6C63FF),
        minHeight:       4,
      ),
    ),
  ]);
}

class _StatsGrid extends StatelessWidget {
  final LiveStats stats;
  const _StatsGrid({required this.stats});

  @override
  Widget build(BuildContext context) {
    final mins = stats.remainingSecs ~/ 60;
    final secs = stats.remainingSecs  % 60;
    return Row(children: [
      _StatBox(value: '${stats.videos}',  label: 'Video',  color: const Color(0xFF6C63FF)),
      const SizedBox(width: 12),
      _StatBox(value: '${stats.likes}',   label: 'Thích',  color: const Color(0xFFEC4899)),
      const SizedBox(width: 12),
      _StatBox(value: '${stats.follows}', label: 'Theo',   color: const Color(0xFF10B981)),
      const SizedBox(width: 12),
      _StatBox(
        value: '$mins:${secs.toString().padLeft(2,'0')}',
        label: 'Còn lại', color: const Color(0xFFF59E0B),
      ),
    ]);
  }
}

class _StatBox extends StatelessWidget {
  final String value, label;
  final Color color;
  const _StatBox({required this.value, required this.label, required this.color});
  @override
  Widget build(BuildContext context) => Expanded(child: Container(
    padding: const EdgeInsets.symmetric(vertical: 16),
    decoration: BoxDecoration(
      color:        color.withOpacity(0.08),
      borderRadius: BorderRadius.circular(14),
      border:       Border.all(color: color.withOpacity(0.2)),
    ),
    child: Column(children: [
      Text(value, style: TextStyle(color: color, fontSize: 20, fontWeight: FontWeight.w800)),
      const SizedBox(height: 4),
      Text(label, style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 11)),
    ]),
  ));
}

class _PauseResumeButton extends StatelessWidget {
  final bool isPaused;
  final FarmNotifier notifier;
  const _PauseResumeButton({required this.isPaused, required this.notifier});
  @override
  Widget build(BuildContext context) => SizedBox(
    width: double.infinity, height: 48,
    child: OutlinedButton(
      onPressed: isPaused ? notifier.resume : notifier.pause,
      style: OutlinedButton.styleFrom(
        side: const BorderSide(color: Color(0xFF374151)),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
      child: Text(
        isPaused ? 'Tiếp tục' : 'Tạm dừng',
        style: TextStyle(
          color:      isPaused ? const Color(0xFF10B981) : const Color(0xFF9CA3AF),
          fontWeight: FontWeight.w600,
        ),
      ),
    ),
  );
}

class _ShortcutRow extends StatelessWidget {
  @override
  Widget build(BuildContext context) => Row(children: [
    _Shortcut(Icons.people_rounded,       'Tài khoản', '/accounts', context),
    const SizedBox(width: 12),
    _Shortcut(Icons.bar_chart_rounded,    'Thống kê',  '/stats',    context),
    const SizedBox(width: 12),
    _Shortcut(Icons.terminal_rounded,     'Nhật ký',   '/logs',     context),
  ]);
}

class _Shortcut extends StatelessWidget {
  final IconData icon;
  final String label, path;
  final BuildContext ctx;
  const _Shortcut(this.icon, this.label, this.path, this.ctx);
  @override
  Widget build(BuildContext ctx2) => Expanded(child: GestureDetector(
    onTap: () => ctx.go(path),
    child: Container(
      padding: const EdgeInsets.symmetric(vertical: 14),
      decoration: BoxDecoration(
        color: const Color(0xFF1A1A2E),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(children: [
        Icon(icon, color: const Color(0xFF6C63FF), size: 20),
        const SizedBox(height: 6),
        Text(label, style: const TextStyle(fontSize: 11, color: Color(0xFF9CA3AF))),
      ]),
    ),
  ));
}

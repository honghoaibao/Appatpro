import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

// ════════════════════════════════════════════════════════════════
//  Permission Screen — dẫn dắt từng bước rõ ràng, ít text
// ════════════════════════════════════════════════════════════════

class PermissionScreen extends StatefulWidget {
  const PermissionScreen({super.key});
  @override
  State<PermissionScreen> createState() => _State();
}

class _State extends State<PermissionScreen> {
  static const _ch  = MethodChannel('com.atpro/control');
  static const _ev  = EventChannel('com.atpro/events');

  bool _serviceOn   = false;
  bool _checking    = false;
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    _check();
    _sub = _ev.receiveBroadcastStream().listen((e) {
      final m    = Map<String, dynamic>.from(e as Map);
      final type = m['type'] as String?;
      if (type == 'serviceStatus' && m['status'] == 'connected' ||
          type == 'serviceReady'  && m['ready']  == true) {
        _onGranted();
      }
    });
  }

  @override
  void dispose() { _sub?.cancel(); super.dispose(); }

  Future<void> _check() async {
    setState(() => _checking = true);
    try {
      final r  = await _ch.invokeMethod<Map>('getPermissionStatus');
      final on = r?['accessibility'] as bool? ?? false;
      if (on) { _onGranted(); return; }
    } catch (_) {}
    if (mounted) setState(() => _checking = false);
  }

  void _onGranted() {
    if (!mounted) return;
    setState(() { _serviceOn = true; _checking = false; });
    Future.delayed(const Duration(milliseconds: 800), () {
      if (mounted) context.go('/');
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xFF0D0D14),
    body: SafeArea(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 28),
        child: Column(children: [
          const Spacer(flex: 2),

          // Logo
          Container(
            width: 72, height: 72,
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFF6C63FF), Color(0xFFEC4899)],
                begin: Alignment.topLeft, end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(20),
            ),
            child: const Center(child: Text('AT',
                style: TextStyle(color: Colors.white, fontWeight: FontWeight.w800, fontSize: 24))),
          ),
          const SizedBox(height: 24),

          const Text('Cần 1 quyền để bắt đầu',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Colors.white)),
          const SizedBox(height: 10),
          const Text(
            'AT PRO điều khiển TikTok qua\nAccessibility Service của Android.',
            textAlign: TextAlign.center,
            style: TextStyle(color: Color(0xFF9CA3AF), fontSize: 14, height: 1.6),
          ),

          const Spacer(flex: 2),

          // 3 bước — icon + text, không dài
          _Step(num: 1, text: 'Nhấn "Mở cài đặt" bên dưới'),
          const SizedBox(height: 16),
          _Step(num: 2, text: 'Chọn  AT PRO  trong danh sách'),
          const SizedBox(height: 16),
          _Step(num: 3, text: 'Bật công tắc  →  Quay lại app'),

          const Spacer(flex: 2),

          // Status indicator
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 300),
            child: _serviceOn
                ? const _StatusBadge(text: 'Đã kết nối — đang chuyển...', ok: true)
                : _checking
                    ? const _StatusBadge(text: 'Đang kiểm tra...', ok: false)
                    : const _StatusBadge(text: 'Chờ bật Accessibility Service', ok: false),
          ),
          const SizedBox(height: 20),

          // CTA
          SizedBox(
            width: double.infinity, height: 54,
            child: FilledButton(
              onPressed: () => _ch.invokeMethod('openAccessibilitySettings'),
              style:     FilledButton.styleFrom(
                backgroundColor: const Color(0xFF6C63FF),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
              ),
              child: const Text('Mở cài đặt',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            ),
          ),
          const SizedBox(height: 12),

          TextButton(
            onPressed: _check,
            child: const Text('Đã bật rồi — kiểm tra lại',
                style: TextStyle(color: Color(0xFF6B7280), fontSize: 13)),
          ),
          const SizedBox(height: 20),
        ]),
      ),
    ),
  );
}

// ── Widgets ───────────────────────────────────────────────────

class _Step extends StatelessWidget {
  final int num;
  final String text;
  const _Step({required this.num, required this.text});

  @override
  Widget build(BuildContext context) => Row(children: [
    Container(
      width: 32, height: 32,
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xFF6C63FF), Color(0xFF4B44CC)],
          begin: Alignment.topLeft, end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Center(child: Text('$num', style: const TextStyle(
          color: Colors.white, fontWeight: FontWeight.w700, fontSize: 14))),
    ),
    const SizedBox(width: 14),
    Expanded(child: Text(text, style: const TextStyle(
        color: Color(0xFFE5E7EB), fontSize: 15, height: 1.4))),
  ]);
}

class _StatusBadge extends StatelessWidget {
  final String text;
  final bool ok;
  const _StatusBadge({required this.text, required this.ok});

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
    decoration: BoxDecoration(
      color:        ok
          ? const Color(0xFF10B981).withOpacity(0.1)
          : const Color(0xFF374151).withOpacity(0.5),
      borderRadius: BorderRadius.circular(30),
    ),
    child: Row(mainAxisSize: MainAxisSize.min, children: [
      Container(
        width: 7, height: 7,
        decoration: BoxDecoration(
          color:  ok ? const Color(0xFF10B981) : const Color(0xFF6B7280),
          shape:  BoxShape.circle,
        ),
      ),
      const SizedBox(width: 8),
      Text(text, style: TextStyle(
        color:      ok ? const Color(0xFF10B981) : const Color(0xFF9CA3AF),
        fontSize:   13, fontWeight: FontWeight.w500,
      )),
    ]),
  );
}

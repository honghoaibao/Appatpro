import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import '../services/providers.dart';

// ════════════════════════════════════════════════════════════════
//  Log Screen — phân cấp rõ hơn, dễ đọc khi nhiều log
// ════════════════════════════════════════════════════════════════

class LogScreen extends ConsumerWidget {
  const LogScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final logs     = ref.watch(logProvider);
    final notifier = ref.read(logProvider.notifier);

    return Scaffold(
      backgroundColor: const Color(0xFF0D0D14),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0D0D14),
        title: Row(children: [
          const Text('Nhật ký', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(width: 10),
          if (logs.isNotEmpty)
            _CountBadge(logs.length),
        ]),
        actions: [
          // Filter by level
          _LevelFilter(),
          IconButton(
            icon:    const Icon(Icons.copy_all_rounded, size: 20),
            tooltip: 'Sao chép tất cả',
            onPressed: () {
              final text = logs.map((l) =>
                '[${DateFormat('HH:mm:ss').format(l.time)}] ${l.message}').join('\n');
              Clipboard.setData(ClipboardData(text: text));
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Đã sao chép'), duration: Duration(seconds: 1),
                    behavior: SnackBarBehavior.floating));
            },
          ),
          IconButton(
            icon:    const Icon(Icons.delete_sweep_rounded, size: 20),
            tooltip: 'Xóa',
            onPressed: notifier.clear,
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: logs.isEmpty
          ? const _EmptyLog()
          : _LogListView(logs: logs),
    );
  }
}

// ── Level Filter ──────────────────────────────────────────────

final _levelFilterProvider = StateProvider<String?>((ref) => null);

class _LevelFilter extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final current = ref.watch(_levelFilterProvider);
    return PopupMenuButton<String?>(
      icon:            const Icon(Icons.filter_list_rounded, size: 20),
      initialValue:    current,
      onSelected:      (v) => ref.read(_levelFilterProvider.notifier).state = v,
      itemBuilder:     (_) => [
        const PopupMenuItem(value: null,        child: Text('Tất cả')),
        const PopupMenuItem(value: 'INFO',      child: Text('Thông tin')),
        const PopupMenuItem(value: 'SUCCESS',   child: Text('Thành công')),
        const PopupMenuItem(value: 'WARNING',   child: Text('Cảnh báo')),
        const PopupMenuItem(value: 'ERROR',     child: Text('Lỗi')),
      ],
    );
  }
}

// ── Log List ──────────────────────────────────────────────────

class _LogListView extends ConsumerStatefulWidget {
  final List<LogEntry> logs;
  const _LogListView({required this.logs});
  @override
  ConsumerState<_LogListView> createState() => _LogListState();
}

class _LogListState extends ConsumerState<_LogListView> {
  final _scroll     = ScrollController();
  bool  _autoScroll = true;

  @override
  void didUpdateWidget(_LogListView old) {
    super.didUpdateWidget(old);
    if (_autoScroll && widget.logs.length != old.logs.length) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scroll.hasClients) _scroll.jumpTo(0);
      });
    }
  }

  @override
  void dispose() { _scroll.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final filterLevel = ref.watch(_levelFilterProvider);
    final filtered    = filterLevel == null
        ? widget.logs
        : widget.logs.where((l) => l.level == filterLevel).toList();

    return Stack(children: [
      ListView.builder(
        controller:  _scroll,
        reverse:     true,                      // newest on top
        padding:     const EdgeInsets.fromLTRB(12, 8, 12, 80),
        itemCount:   filtered.length,
        itemBuilder: (_, i) => _LogRow(entry: filtered[i]),
      ),

      // Auto-scroll toggle
      Positioned(
        right: 16, bottom: 16,
        child: FloatingActionButton.small(
          onPressed:       () => setState(() => _autoScroll = !_autoScroll),
          backgroundColor: _autoScroll
              ? const Color(0xFF6C63FF)
              : const Color(0xFF374151),
          elevation: 2,
          child: Icon(
            _autoScroll
                ? Icons.vertical_align_bottom_rounded
                : Icons.pause_rounded,
            size: 18,
          ),
        ),
      ),
    ]);
  }
}

// ── Log Row — spacing + visual weight rõ theo level ──────────

class _LogRow extends StatelessWidget {
  final LogEntry entry;
  const _LogRow({required this.entry});

  // Màu sắc + icon theo level
  Color get _color => switch (entry.level) {
    'ERROR'   => const Color(0xFFEF4444),
    'WARNING' => const Color(0xFFF59E0B),
    'SUCCESS' => const Color(0xFF10B981),
    _         => const Color(0xFF6B7280),
  };

  IconData get _icon => switch (entry.level) {
    'ERROR'   => Icons.cancel_outlined,
    'WARNING' => Icons.warning_amber_rounded,
    'SUCCESS' => Icons.check_circle_outline_rounded,
    _         => Icons.circle_outlined,
  };

  bool get _isImportant =>
      entry.level == 'ERROR' || entry.level == 'WARNING' || entry.level == 'SUCCESS';

  @override
  Widget build(BuildContext context) {
    final timeStr = DateFormat('HH:mm:ss').format(entry.time);

    return Container(
      margin:  const EdgeInsets.only(bottom: 4),
      padding: _isImportant
          ? const EdgeInsets.symmetric(horizontal: 10, vertical: 8)
          : const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: _isImportant
          ? BoxDecoration(
              color:        _color.withOpacity(0.06),
              borderRadius: BorderRadius.circular(8),
              border:       Border(left: BorderSide(color: _color.withOpacity(0.6), width: 2)),
            )
          : null,
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // Icon (chỉ với important logs)
        if (_isImportant) ...[
          Padding(
            padding: const EdgeInsets.only(top: 1, right: 6),
            child:   Icon(_icon, color: _color, size: 13),
          ),
        ] else
          const SizedBox(width: 19),

        // Timestamp
        SizedBox(
          width: 62,
          child: Text(timeStr, style: const TextStyle(
            color: Color(0xFF4B5563), fontSize: 10.5, fontFamily: 'monospace')),
        ),

        // Message
        Expanded(child: Text(
          entry.message,
          style: TextStyle(
            color:      _color,
            fontSize:   12.5,
            fontFamily: 'monospace',
            height:     1.45,
            fontWeight: _isImportant ? FontWeight.w500 : FontWeight.normal,
          ),
        )),
      ]),
    );
  }
}

class _CountBadge extends StatelessWidget {
  final int count;
  const _CountBadge(this.count);
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
    decoration: BoxDecoration(
      color:        const Color(0xFF6C63FF).withOpacity(0.15),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text('$count', style: const TextStyle(color: Color(0xFF6C63FF), fontSize: 11, fontWeight: FontWeight.w600)),
  );
}

class _EmptyLog extends StatelessWidget {
  const _EmptyLog();
  @override
  Widget build(BuildContext context) => Center(child: Column(
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      const Icon(Icons.receipt_long_rounded, size: 52, color: Color(0xFF374151)),
      const SizedBox(height: 14),
      const Text('Chưa có nhật ký', style: TextStyle(color: Color(0xFF6B7280), fontSize: 14)),
      const SizedBox(height: 6),
      const Text('Nhật ký xuất hiện khi farm đang chạy',
          style: TextStyle(color: Color(0xFF4B5563), fontSize: 12)),
    ],
  ));
}

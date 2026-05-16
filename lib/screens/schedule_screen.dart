import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// ════════════════════════════════════════════════════════════════
//  ScheduleScreen — Fix 2: load + sync với WorkManager
// ════════════════════════════════════════════════════════════════

class ScheduleScreen extends StatefulWidget {
  const ScheduleScreen({super.key});
  @override
  State<ScheduleScreen> createState() => _ScheduleScreenState();
}

class _ScheduleScreenState extends State<ScheduleScreen> {
  static const _ch = MethodChannel('com.atpro/control');

  List<_ScheduleEntry> _schedules = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadSchedules(); // Fix 2: load từ native khi khởi động
  }

  // Fix 2: Load schedules đã lưu từ WorkManager/Room DB
  Future<void> _loadSchedules() async {
    setState(() => _loading = true);
    try {
      final result = await _ch.invokeMethod<List>('getSchedules');
      final list   = (result ?? []).map((e) {
        final m    = Map<String, dynamic>.from(e as Map);
        final days = (m['days'] as List?)?.cast<int>() ?? [1,2,3,4,5,6,7];
        return _ScheduleEntry(
          id:      m['id']    as String? ?? '',
          label:   m['label'] as String? ?? 'Farm',
          time:    TimeOfDay(hour: m['hour'] as int? ?? 8, minute: m['minute'] as int? ?? 0),
          days:    days,
          enabled: m['enabled'] as bool? ?? true,
        );
      }).toList();
      setState(() { _schedules = list; _loading = false; });
    } catch (_) {
      setState(() => _loading = false);
    }
  }

  Future<void> _registerSchedule(_ScheduleEntry entry) async {
    try {
      await _ch.invokeMethod('setSchedule', {
        'id':      entry.id,
        'label':   entry.label,
        'hour':    entry.time.hour,
        'minute':  entry.time.minute,
        'days':    entry.days,
        'enabled': entry.enabled,
        'accounts': <String>[],
      });
    } catch (e) {
      debugPrint('setSchedule: $e');
    }
  }

  Future<void> _cancelSchedule(String id) async {
    try {
      await _ch.invokeMethod('cancelSchedule', {'id': id});
    } catch (_) {}
  }

  // Fix 2: Toggle sync WorkManager — bật gọi setSchedule, tắt gọi cancelSchedule
  Future<void> _toggleSchedule(int index) async {
    final entry  = _schedules[index];
    final updated = entry.copyWith(enabled: !entry.enabled);
    setState(() => _schedules[index] = updated);
    if (updated.enabled) {
      await _registerSchedule(updated);
    } else {
      await _cancelSchedule(updated.id);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Lịch Farm Tự Động'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh_rounded), onPressed: _loadSchedules),
          const SizedBox(width: 4),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _showAddDialog,
        backgroundColor: const Color(0xFF6C63FF),
        child: const Icon(Icons.add_rounded),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _schedules.isEmpty
              ? const _EmptySchedules()
              : ListView.separated(
                  padding: const EdgeInsets.all(16),
                  itemCount:        _schedules.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 10),
                  itemBuilder: (context, i) => _ScheduleCard(
                    entry:    _schedules[i],
                    onToggle: () => _toggleSchedule(i),  // Fix 2: sync WorkManager
                    onDelete: () async {
                      await _cancelSchedule(_schedules[i].id);
                      setState(() => _schedules.removeAt(i));
                    },
                  ),
                ),
    );
  }

  void _showAddDialog() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF1A1A2E),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => _AddScheduleSheet(
        onSave: (entry) async {
          setState(() => _schedules.add(entry));
          await _registerSchedule(entry);
        },
      ),
    );
  }
}

// ── Data model ────────────────────────────────────────────────

class _ScheduleEntry {
  final String id;
  final String label;
  final TimeOfDay time;
  final List<int> days;
  final bool enabled;

  const _ScheduleEntry({
    required this.id, required this.label,
    required this.time, required this.days, required this.enabled,
  });

  _ScheduleEntry copyWith({bool? enabled}) => _ScheduleEntry(
    id: id, label: label, time: time, days: days,
    enabled: enabled ?? this.enabled,
  );

  String get timeStr =>
      '${time.hour.toString().padLeft(2,'0')}:${time.minute.toString().padLeft(2,'0')}';

  String get daysStr {
    const n = ['T2','T3','T4','T5','T6','T7','CN'];
    if (days.length == 7) return 'Hàng ngày';
    return days.map((d) => n[d - 1]).join(' • ');
  }
}

// ── Schedule Card ─────────────────────────────────────────────

class _ScheduleCard extends StatelessWidget {
  final _ScheduleEntry entry;
  final VoidCallback onToggle;
  final VoidCallback onDelete;
  const _ScheduleCard({required this.entry, required this.onToggle, required this.onDelete});

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 8, 14),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(entry.timeStr, style: TextStyle(
              fontSize: 28, fontWeight: FontWeight.w700,
              color: entry.enabled ? Colors.white : const Color(0xFF6B7280),
            )),
            const SizedBox(height: 4),
            Text(entry.label,    style: const TextStyle(fontWeight: FontWeight.w500, fontSize: 14)),
            const SizedBox(height: 2),
            Text(entry.daysStr,  style: const TextStyle(color: Color(0xFF6B7280), fontSize: 12)),
          ]),
        ),
        IconButton(
          icon: const Icon(Icons.delete_outline_rounded, size: 20, color: Color(0xFF6B7280)),
          onPressed: onDelete,
        ),
        Switch(
          value:     entry.enabled,
          onChanged: (_) => onToggle(),  // Fix 2: gọi onToggle (sync WorkManager)
          activeColor: const Color(0xFF6C63FF),
        ),
      ]),
    ),
  );
}

// ── Add Schedule Sheet ────────────────────────────────────────

class _AddScheduleSheet extends StatefulWidget {
  final Future<void> Function(_ScheduleEntry) onSave;
  const _AddScheduleSheet({required this.onSave});
  @override
  State<_AddScheduleSheet> createState() => _AddScheduleSheetState();
}

class _AddScheduleSheetState extends State<_AddScheduleSheet> {
  TimeOfDay _time   = const TimeOfDay(hour: 8, minute: 0);
  final _labelCtrl  = TextEditingController(text: 'Farm buổi sáng');
  List<int> _days   = [1, 2, 3, 4, 5];
  bool _saving      = false;

  static const _dayLabels = ['T2','T3','T4','T5','T6','T7','CN'];

  @override
  void dispose() { _labelCtrl.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(
      left: 20, right: 20, top: 24,
      bottom: MediaQuery.of(context).viewInsets.bottom + 24,
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Center(child: Container(
          width: 40, height: 4,
          decoration: BoxDecoration(color: const Color(0xFF374151), borderRadius: BorderRadius.circular(2)),
        )),
        const SizedBox(height: 20),
        const Text('Thêm lịch farm', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
        const SizedBox(height: 20),

        // Time picker
        GestureDetector(
          onTap: () async {
            final p = await showTimePicker(
              context: context, initialTime: _time,
              builder: (ctx, child) => Theme(
                data: Theme.of(ctx).copyWith(
                  colorScheme: const ColorScheme.dark(primary: Color(0xFF6C63FF))),
                child: child!,
              ),
            );
            if (p != null) setState(() => _time = p);
          },
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 20),
            decoration: BoxDecoration(
              color: const Color(0xFF6C63FF).withOpacity(0.1),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0xFF6C63FF).withOpacity(0.3)),
            ),
            child: Center(child: Text(
              '${_time.hour.toString().padLeft(2,'0')}:${_time.minute.toString().padLeft(2,'0')}',
              style: const TextStyle(fontSize: 40, fontWeight: FontWeight.w800,
                  color: Color(0xFF6C63FF), letterSpacing: 2),
            )),
          ),
        ),
        const SizedBox(height: 16),

        TextField(
          controller: _labelCtrl,
          decoration: const InputDecoration(labelText: 'Tên lịch', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 16),

        const Text('Các ngày', style: TextStyle(fontSize: 13, color: Color(0xFF9CA3AF))),
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: List.generate(7, (i) {
            final day = i + 1;
            final sel = _days.contains(day);
            return GestureDetector(
              onTap: () => setState(() {
                sel ? _days.remove(day) : (_days..add(day)..sort());
              }),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                width: 40, height: 40,
                decoration: BoxDecoration(
                  color: sel ? const Color(0xFF6C63FF) : const Color(0xFF374151),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Center(child: Text(_dayLabels[i], style: TextStyle(
                  fontSize: 12, fontWeight: FontWeight.w600,
                  color: sel ? Colors.white : const Color(0xFF6B7280),
                ))),
              ),
            );
          }),
        ),
        const SizedBox(height: 24),

        FilledButton(
          onPressed: (_days.isEmpty || _saving) ? null : () async {
            setState(() => _saving = true);
            final entry = _ScheduleEntry(
              id:      DateTime.now().millisecondsSinceEpoch.toString(),
              label:   _labelCtrl.text.trim().isEmpty ? 'Farm' : _labelCtrl.text.trim(),
              time:    _time, days: _days, enabled: true,
            );
            await widget.onSave(entry);
            if (mounted) Navigator.pop(context);
          },
          style: FilledButton.styleFrom(
            backgroundColor: const Color(0xFF6C63FF),
            padding: const EdgeInsets.symmetric(vertical: 16),
          ),
          child: _saving
              ? const SizedBox(width: 20, height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Lưu lịch', style: TextStyle(fontSize: 15)),
        ),
      ],
    ),
  );
}

class _EmptySchedules extends StatelessWidget {
  const _EmptySchedules();
  @override
  Widget build(BuildContext context) => Center(child: Column(
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      const Icon(Icons.schedule_rounded, size: 60, color: Color(0xFF374151)),
      const SizedBox(height: 16),
      const Text('Chưa có lịch nào', style: TextStyle(color: Color(0xFF6B7280))),
      const SizedBox(height: 8),
      const Text('Nhấn + để thêm lịch farm tự động',
          style: TextStyle(color: Color(0xFF4B5563), fontSize: 13)),
    ],
  ));
}

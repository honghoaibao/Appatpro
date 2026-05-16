import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/providers.dart';
import '../widgets/app_illustrations.dart';

// ════════════════════════════════════════════════════════════════
//  Accounts — thoáng hơn, phân tầng rõ: search → list → action
// ════════════════════════════════════════════════════════════════

class AccountsScreen extends ConsumerStatefulWidget {
  const AccountsScreen({super.key});
  @override
  ConsumerState<AccountsScreen> createState() => _AccountsScreenState();
}

class _AccountsScreenState extends ConsumerState<AccountsScreen> {
  static const _ch  = MethodChannel('com.atpro/control');
  final _searchCtrl = TextEditingController();
  String _query  = '';
  String _filter = 'all';

  @override
  void dispose() { _searchCtrl.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final accounts = ref.watch(accountsProvider).valueOrNull ?? [];
    final filtered = _applyFilter(accounts);

    return Scaffold(
      backgroundColor: const Color(0xFF0D0D14),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0D0D14),
        title: const Text('Tài khoản', style: TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded, size: 20),
            onPressed: () => ref.invalidate(accountsProvider),
          ),
          const SizedBox(width: 4),
        ],
      ),

      // FAB thêm tài khoản — rõ, không cạnh tranh với list
      floatingActionButton: FloatingActionButton(
        onPressed:       () => _showAdd(context),
        backgroundColor: const Color(0xFF6C63FF),
        elevation:       0,
        child:           const Icon(Icons.add_rounded),
      ),

      body: Column(children: [
        // Search bar — 1 hàng, gọn
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
          child: TextField(
            controller:  _searchCtrl,
            onChanged:   (v) => setState(() => _query = v.toLowerCase()),
            decoration:  InputDecoration(
              hintText:      '@username',
              hintStyle:     const TextStyle(color: Color(0xFF4B5563)),
              prefixIcon:    const Icon(Icons.search_rounded, size: 18, color: Color(0xFF6B7280)),
              filled:        true,
              fillColor:     const Color(0xFF1A1A2E),
              contentPadding: const EdgeInsets.symmetric(vertical: 10),
              border:         OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide:   BorderSide.none,
              ),
              suffixIcon: _query.isEmpty ? null : IconButton(
                icon: const Icon(Icons.clear_rounded, size: 16, color: Color(0xFF6B7280)),
                onPressed: () { _searchCtrl.clear(); setState(() => _query = ''); },
              ),
            ),
          ),
        ),

        // Filter chips — scroll ngang
        SizedBox(
          height: 44,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding:         const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            children:        [
              _Chip(label: 'Tất cả',     value: 'all',        current: _filter, count: accounts.length,
                    onTap: (v) => setState(() => _filter = v)),
              _Chip(label: 'Hoạt động',  value: 'active',     current: _filter,
                    count: accounts.where((a) => a['status'] == 'active').length,
                    onTap: (v) => setState(() => _filter = v)),
              _Chip(label: 'Checkpoint', value: 'checkpoint', current: _filter,
                    count: accounts.where((a) => a['status'] == 'checkpoint').length,
                    onTap: (v) => setState(() => _filter = v)),
              _Chip(label: 'Bị khóa',   value: 'banned',     current: _filter,
                    count: accounts.where((a) => a['status'] == 'banned').length,
                    onTap: (v) => setState(() => _filter = v)),
            ],
          ),
        ),

        // List
        Expanded(
          child: filtered.isEmpty
              ? EmptyAccountsWidget(onAdd: () => _showAdd(context))
              : RefreshIndicator(
                  onRefresh: () async => ref.invalidate(accountsProvider),
                  child: ListView.separated(
                    padding:          const EdgeInsets.fromLTRB(16, 4, 16, 100),
                    itemCount:        filtered.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder:      (_, i)  => _AccountTile(
                      account:  filtered[i],
                      onDelete: () => _delete(context, filtered[i]['username'] as String),
                      onToggle: () => _toggle(filtered[i]),
                    ),
                  ),
                ),
        ),
      ]),
    );
  }

  List<Map<String, dynamic>> _applyFilter(List<Map<String, dynamic>> list) =>
      list.where((a) {
        final u  = (a['username'] as String? ?? '').toLowerCase();
        final st = a['status'] as String? ?? 'active';
        return (_query.isEmpty || u.contains(_query)) &&
               (_filter == 'all' || st == _filter);
      }).toList();

  void _showAdd(BuildContext context) {
    final ctrl = TextEditingController();
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: const Color(0xFF1A1A2E),
        title:   const Text('Thêm tài khoản'),
        content: TextField(
          controller:  ctrl,
          autofocus:   true,
          decoration:  const InputDecoration(
            hintText:   'username (không cần @)',
            prefixText: '@',
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child:     const Text('Hủy')),
          FilledButton(
            onPressed: () async {
              final u = ctrl.text.trim().replaceAll('@', '');
              if (u.isEmpty) return;
              await _ch.invokeMethod('addAccount', {'username': u});
              if (context.mounted) Navigator.pop(context);
              ref.invalidate(accountsProvider);
            },
            style: FilledButton.styleFrom(backgroundColor: const Color(0xFF6C63FF)),
            child: const Text('Thêm'),
          ),
        ],
      ),
    );
  }

  Future<void> _delete(BuildContext context, String username) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: const Color(0xFF1A1A2E),
        title:   const Text('Xóa tài khoản?'),
        content: Text('@$username sẽ bị xóa khỏi danh sách.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Hủy')),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            style:     FilledButton.styleFrom(backgroundColor: const Color(0xFFEF4444)),
            child:     const Text('Xóa'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await _ch.invokeMethod('deleteAccount', {'username': username});
    ref.invalidate(accountsProvider);
  }

  Future<void> _toggle(Map<String, dynamic> account) async {
    final isChk = account['status'] == 'checkpoint';
    await _ch.invokeMethod('setCheckpoint', {
      'username':   account['username'],
      'checkpoint': !isChk,
    });
    ref.invalidate(accountsProvider);
  }
}

// ── Widgets ───────────────────────────────────────────────────

class _Chip extends StatelessWidget {
  final String label, value, current;
  final int count;
  final void Function(String) onTap;
  const _Chip({required this.label, required this.value, required this.current,
               required this.count, required this.onTap});

  bool get selected => value == current;

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: () => onTap(value),
    child: AnimatedContainer(
      duration: const Duration(milliseconds: 150),
      margin:   const EdgeInsets.only(right: 8),
      padding:  const EdgeInsets.symmetric(horizontal: 14),
      decoration: BoxDecoration(
        color:        selected
            ? const Color(0xFF6C63FF).withOpacity(0.2)
            : Colors.transparent,
        borderRadius: BorderRadius.circular(20),
        border:       Border.all(
          color: selected ? const Color(0xFF6C63FF) : const Color(0xFF374151),
        ),
      ),
      child: Row(children: [
        Text(label, style: TextStyle(
          color:      selected ? const Color(0xFF6C63FF) : const Color(0xFF9CA3AF),
          fontSize:   12,
          fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
        )),
        if (count > 0) ...[
          const SizedBox(width: 5),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
            decoration: BoxDecoration(
              color:        selected
                  ? const Color(0xFF6C63FF).withOpacity(0.3)
                  : const Color(0xFF374151),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text('$count', style: TextStyle(
              color:    selected ? const Color(0xFF6C63FF) : const Color(0xFF6B7280),
              fontSize: 10, fontWeight: FontWeight.w600,
            )),
          ),
        ],
      ]),
    ),
  );
}

class _AccountTile extends StatelessWidget {
  final Map<String, dynamic> account;
  final VoidCallback onDelete, onToggle;
  const _AccountTile({required this.account, required this.onDelete, required this.onToggle});

  Color get _color => switch (account['status'] as String? ?? 'active') {
    'checkpoint' => const Color(0xFFF59E0B),
    'banned'     => const Color(0xFFEF4444),
    _            => const Color(0xFF10B981),
  };

  String get _statusText => switch (account['status'] as String? ?? 'active') {
    'checkpoint' => 'Checkpoint',
    'banned'     => 'Bị khóa',
    _            => 'Hoạt động',
  };

  @override
  Widget build(BuildContext context) {
    final username = account['username'] as String? ?? '';
    final sessions = account['sessionsCount'] as int? ?? 0;
    final likes    = account['totalLikes']    as int? ?? 0;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color:        const Color(0xFF1A1A2E),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(children: [
        // Avatar
        Container(
          width: 42, height: 42,
          decoration: BoxDecoration(
            color:  _color.withOpacity(0.12),
            shape:  BoxShape.circle,
          ),
          child: Center(child: Text(
            username.isNotEmpty ? username[0].toUpperCase() : '?',
            style: TextStyle(color: _color, fontWeight: FontWeight.w700, fontSize: 16),
          )),
        ),
        const SizedBox(width: 14),

        // Info
        Expanded(child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('@$username', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
            const SizedBox(height: 4),
            Row(children: [
              Container(width: 6, height: 6,
                  decoration: BoxDecoration(color: _color, shape: BoxShape.circle)),
              const SizedBox(width: 5),
              Text(_statusText, style: TextStyle(color: _color, fontSize: 11)),
              const SizedBox(width: 10),
              Text('$sessions phiên  ·  $likes ♥',
                  style: const TextStyle(color: Color(0xFF6B7280), fontSize: 11)),
            ]),
          ],
        )),

        // Actions — icon only, tap zone rộng
        _IconAction(
          icon:    account['status'] == 'checkpoint'
              ? Icons.check_circle_outline_rounded
              : Icons.warning_amber_rounded,
          color:   account['status'] == 'checkpoint'
              ? const Color(0xFF10B981)
              : const Color(0xFF6B7280),
          onTap:   onToggle,
          tooltip: account['status'] == 'checkpoint' ? 'Đặt lại' : 'Đánh dấu checkpoint',
        ),
        _IconAction(
          icon:    Icons.delete_outline_rounded,
          color:   const Color(0xFF6B7280),
          onTap:   onDelete,
          tooltip: 'Xóa',
        ),
      ]),
    );
  }
}

class _IconAction extends StatelessWidget {
  final IconData icon;
  final Color color;
  final VoidCallback onTap;
  final String tooltip;
  const _IconAction({required this.icon, required this.color,
                     required this.onTap, required this.tooltip});
  @override
  Widget build(BuildContext context) => Tooltip(
    message: tooltip,
    child:   InkWell(
      onTap:        onTap,
      borderRadius: BorderRadius.circular(20),
      child:        Padding(
        padding: const EdgeInsets.all(8),
        child:   Icon(icon, color: color, size: 20),
      ),
    ),
  );
}

import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'at_pro_bridge.dart';

// ════════════════════════════════════════════════════════════════
//  providers.dart — Phase 3 (Local DB, no Supabase)
//  Tất cả data đọc từ Room DB qua MethodChannel
// ════════════════════════════════════════════════════════════════

final _bridge = AtProBridge();

// ── Service Status ────────────────────────────────────────────
// Combines initial poll + realtime stream so connected state works on cold start

final serviceStatusProvider = FutureProvider<Map<String, dynamic>>((ref) async {
  // Poll once on startup
  try {
    final status = await const MethodChannel('com.atpro/control')
        .invokeMethod<Map>('getServiceStatus');
    if (status != null) {
      return {
        'connected': status['connected'] as bool? ?? false,
        'status':    (status['connected'] == true) ? 'connected' : 'disconnected',
      };
    }
  } catch (_) {}
  return {'connected': false, 'status': 'disconnected'};
});

// Runtime service status changes are merged into FarmNotifier via serviceStatus stream

// ── Farm State ────────────────────────────────────────────────

class FarmState {
  final bool isRunning;
  final bool isPaused;
  final String? currentAccount;
  final int currentIndex;
  final int totalAccounts;
  final String? stopReason;
  const FarmState({
    this.isRunning = false, this.isPaused = false,
    this.currentAccount, this.currentIndex = 0,
    this.totalAccounts = 0, this.stopReason,
  });
  FarmState copyWith({bool? isRunning, bool? isPaused, String? currentAccount,
      int? currentIndex, int? totalAccounts, String? stopReason}) =>
    FarmState(
      isRunning: isRunning ?? this.isRunning,
      isPaused: isPaused ?? this.isPaused,
      currentAccount: currentAccount ?? this.currentAccount,
      currentIndex: currentIndex ?? this.currentIndex,
      totalAccounts: totalAccounts ?? this.totalAccounts,
      stopReason: stopReason ?? this.stopReason,
    );
}

class FarmNotifier extends Notifier<FarmState> {
  late StreamSubscription _s1, _s2, _s3;
  @override
  FarmState build() {
    _s1 = _bridge.farmStatus.listen((e) {
      final status = e['status'] as String?;
      state = state.copyWith(
        isRunning:    status == 'started',
        stopReason:   status == 'stopped' ? e['reason'] as String? : null,
        totalAccounts: (e['total'] as int?) ?? state.totalAccounts,
        // reset isPaused khi farm stop
        isPaused: status == 'stopped' ? false : state.isPaused,
      );
    });
    _s2 = _bridge.accountStream.listen((e) {
      state = state.copyWith(
        currentAccount: e['account'] as String?,
        currentIndex:   (e['index']   as int?) ?? state.currentIndex,
        totalAccounts:  (e['total']   as int?) ?? state.totalAccounts,
      );
    });
    // Fix 4: lắng nghe pauseStatus event từ Kotlin
    _s3 = _bridge.eventsOfType('pauseStatus').listen((e) {
      state = state.copyWith(isPaused: e['paused'] as bool? ?? false);
    });
    ref.onDispose(() { _s1.cancel(); _s2.cancel(); _s3.cancel(); });
    return const FarmState();
  }
  Future<void> start(List<String> accounts) => _bridge.startFarm(accounts);
  Future<void> stop()   => _bridge.stopFarm();
  Future<void> pause()  async {
    await _bridge.pauseFarm();
    state = state.copyWith(isPaused: true);   // optimistic update
  }
  Future<void> resume() async {
    await _bridge.resumeFarm();
    state = state.copyWith(isPaused: false);  // optimistic update
  }
}

final farmProvider = NotifierProvider<FarmNotifier, FarmState>(FarmNotifier.new);

// ── Live Stats ────────────────────────────────────────────────

class LiveStats {
  final int videos, likes, follows, remainingSecs;
  const LiveStats({this.videos=0, this.likes=0, this.follows=0, this.remainingSecs=0});
  factory LiveStats.fromMap(Map<String, dynamic> m) => LiveStats(
    videos: (m['videos'] as int?) ?? 0,
    likes: (m['likes'] as int?) ?? 0,
    follows: (m['follows'] as int?) ?? 0,
    remainingSecs: (m['remainingSecs'] as int?) ?? 0,
  );
}

final liveStatsProvider = StreamProvider<LiveStats>((ref) =>
  _bridge.statsStream.map(LiveStats.fromMap));

// ── Logs ──────────────────────────────────────────────────────

class LogEntry {
  final String message, level;
  final DateTime time;
  LogEntry({required this.message, required this.level, DateTime? time})
      : time = time ?? DateTime.now();
}

class LogNotifier extends Notifier<List<LogEntry>> {
  late StreamSubscription _sub;
  @override
  List<LogEntry> build() {
    _sub = _bridge.logStream.listen((e) {
      final entry = LogEntry(
        message: e['message'] as String? ?? '',
        level:   e['level']   as String? ?? 'INFO',
      );
      state = [entry, ...state.take(299)];
    });
    ref.onDispose(_sub.cancel);
    return [];
  }
  void clear() => state = [];
}

final logProvider = NotifierProvider<LogNotifier, List<LogEntry>>(LogNotifier.new);

// ── Accounts (từ Room DB qua MethodChannel) ───────────────────

final accountsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  final result = await const MethodChannel('com.atpro/control')
      .invokeMethod<List>('getAccountsFromDb');
  return (result ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

// ── Sessions (từ Room DB) ─────────────────────────────────────

final recentSessionsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  final result = await const MethodChannel('com.atpro/control')
      .invokeMethod<List>('getRecentSessions', {'limit': 50});
  return (result ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

// ── Daily Stats (từ Room DB) ──────────────────────────────────

final dailyStatsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  final result = await const MethodChannel('com.atpro/control')
      .invokeMethod<List>('getDailyStats', {'days': 30});
  return (result ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
});

// ── Totals (từ Room DB) ───────────────────────────────────────

final totalsProvider = FutureProvider<Map<String, dynamic>>((ref) async {
  final result = await const MethodChannel('com.atpro/control')
      .invokeMethod<Map>('getTotals', {'days': 30});
  return result != null ? Map<String, dynamic>.from(result) : {};
});

// ── WS Server info ────────────────────────────────────────────

final wsServerProvider = StreamProvider<Map<String, dynamic>>((ref) =>
  _bridge.eventsOfType('wsServer'));

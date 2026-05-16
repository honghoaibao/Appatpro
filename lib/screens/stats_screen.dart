import 'package:flutter/material.dart';
import '../widgets/app_illustrations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:intl/intl.dart';
import '../services/providers.dart';

class StatsScreen extends ConsumerStatefulWidget {
  const StatsScreen({super.key});
  @override
  ConsumerState<StatsScreen> createState() => _StatsScreenState();
}

class _StatsScreenState extends ConsumerState<StatsScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tab;

  @override
  void initState()  { super.initState(); _tab = TabController(length: 3, vsync: this); }
  @override
  void dispose()    { _tab.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Stats'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            onPressed: () {
              ref.invalidate(dailyStatsProvider);
              ref.invalidate(recentSessionsProvider);
            },
          ),
        ],
        bottom: TabBar(
          controller: _tab,
          labelColor:        const Color(0xFF6C63FF),
          unselectedLabelColor: const Color(0xFF6B7280),
          indicatorColor:    const Color(0xFF6C63FF),
          indicatorSize:     TabBarIndicatorSize.label,
          tabs: const [
            Tab(text: 'Tổng quan'),
            Tab(text: 'Biểu đồ'),
            Tab(text: 'Phiên farm'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tab,
        children: const [
          _OverviewTab(),
          _ChartTab(),
          _SessionsTab(),
        ],
      ),
    );
  }
}

// ── Tab 1: Overview ───────────────────────────────────────────

class _OverviewTab extends ConsumerWidget {
  const _OverviewTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sessionsAsync = ref.watch(recentSessionsProvider);
    return sessionsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error:   (e, _) => Center(child: Text('$e')),
      data:    (sessions) {
        final totalLikes   = sessions.fold(0, (s, e) => s + (e['likes']   as int? ?? 0));
        final totalFollows = sessions.fold(0, (s, e) => s + (e['follows'] as int? ?? 0));
        final totalVideos  = sessions.fold(0, (s, e) => s + (e['videos_watched'] as int? ?? 0));
        final totalSessions = sessions.length;

        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Big numbers
              GridView.count(
                crossAxisCount:   2,
                shrinkWrap:       true,
                physics:          const NeverScrollableScrollPhysics(),
                mainAxisSpacing:  10,
                crossAxisSpacing: 10,
                childAspectRatio: 1.5,
                children: [
                  _BigStat(label: 'Tổng Likes',    value: _fmt(totalLikes),   icon: Icons.favorite_rounded,    color: const Color(0xFFEC4899)),
                  _BigStat(label: 'Tổng Follows',  value: _fmt(totalFollows), icon: Icons.person_add_rounded,  color: const Color(0xFF10B981)),
                  _BigStat(label: 'Videos',         value: _fmt(totalVideos),  icon: Icons.play_circle_rounded, color: const Color(0xFF6C63FF)),
                  _BigStat(label: 'Phiên farm',        value: '$totalSessions',  icon: Icons.history_rounded,     color: const Color(0xFFF59E0B)),
                ],
              ),
              const SizedBox(height: 20),
              // Avg per session
              _SectionTitle(title: 'Trung bình / Session'),
              const SizedBox(height: 12),
              if (totalSessions > 0) ...[
                _AvgRow(label: 'Likes / session',   value: (totalLikes   / totalSessions).toStringAsFixed(1)),
                _AvgRow(label: 'Follows / session', value: (totalFollows / totalSessions).toStringAsFixed(1)),
                _AvgRow(label: 'Videos / session',  value: (totalVideos  / totalSessions).toStringAsFixed(1)),
              ],
            ],
          ),
        );
      },
    );
  }

  String _fmt(int n) {
    if (n >= 1000) return '${(n / 1000).toStringAsFixed(1)}k';
    return '$n';
  }
}

class _BigStat extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final Color color;
  const _BigStat({required this.label, required this.value, required this.icon, required this.color});

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Icon(icon, color: color, size: 24),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(value, style: TextStyle(color: color, fontSize: 28, fontWeight: FontWeight.w800)),
              Text(label, style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 12)),
            ],
          ),
        ],
      ),
    ),
  );
}

class _AvgRow extends StatelessWidget {
  final String label;
  final String value;
  const _AvgRow({required this.label, required this.value});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: const TextStyle(color: Color(0xFF9CA3AF))),
        Text(value,  style: const TextStyle(fontWeight: FontWeight.w600)),
      ],
    ),
  );
}

// ── Tab 2: Charts ─────────────────────────────────────────────

class _ChartTab extends ConsumerWidget {
  const _ChartTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final statsAsync = ref.watch(dailyStatsProvider);
    return statsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error:   (e, _) => Center(child: Text('$e')),
      data:    (stats) {
        if (stats.isEmpty) return const EmptyStatsWidget();

        final reversed = stats.reversed.toList();
        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _SectionTitle(title: '❤️ Likes 30 ngày gần nhất'),
              const SizedBox(height: 12),
              _LineChart(
                data:  reversed.map((s) => (s['likes'] as int? ?? 0).toDouble()).toList(),
                color: const Color(0xFFEC4899),
              ),
              const SizedBox(height: 28),
              _SectionTitle(title: '👤 Follows 30 ngày gần nhất'),
              const SizedBox(height: 12),
              _LineChart(
                data:  reversed.map((s) => (s['follows'] as int? ?? 0).toDouble()).toList(),
                color: const Color(0xFF10B981),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _LineChart extends StatelessWidget {
  final List<double> data;
  final Color color;
  const _LineChart({required this.data, required this.color});

  @override
  Widget build(BuildContext context) {
    final spots = data.asMap().entries
        .map((e) => FlSpot(e.key.toDouble(), e.value))
        .toList();

    return SizedBox(
      height: 180,
      child: LineChart(
        LineChartData(
          gridData:     FlGridData(show: true,
              getDrawingHorizontalLine: (_) => FlLine(color: const Color(0xFF374151), strokeWidth: 0.5),
              getDrawingVerticalLine:   (_) => FlLine(color: Colors.transparent)),
          titlesData:   FlTitlesData(
            leftTitles:   AxisTitles(sideTitles: SideTitles(showTitles: true, reservedSize: 36,
                getTitlesWidget: (v, _) => Text('${v.toInt()}', style: const TextStyle(color: Color(0xFF6B7280), fontSize: 10)))),
            bottomTitles: AxisTitles(sideTitles: SideTitles(showTitles: false)),
            rightTitles:  AxisTitles(sideTitles: SideTitles(showTitles: false)),
            topTitles:    AxisTitles(sideTitles: SideTitles(showTitles: false)),
          ),
          borderData: FlBorderData(show: false),
          lineBarsData: [
            LineChartBarData(
              spots:          spots,
              isCurved:       true,
              color:          color,
              barWidth:       2.5,
              dotData:        FlDotData(show: false),
              belowBarData:   BarAreaData(
                show:  true,
                color: color.withOpacity(0.1),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Tab 3: Sessions ───────────────────────────────────────────

class _SessionsTab extends ConsumerWidget {
  const _SessionsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sessionsAsync = ref.watch(recentSessionsProvider);
    return sessionsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error:   (e, _) => Center(child: Text('$e')),
      data:    (sessions) {
        if (sessions.isEmpty) return const EmptyStatsWidget();
        return ListView.separated(
          padding: const EdgeInsets.all(16),
          itemCount:       sessions.length,
          separatorBuilder: (_, __) => const SizedBox(height: 8),
          itemBuilder: (context, i) => _SessionTile(session: sessions[i]),
        );
      },
    );
  }
}

class _SessionTile extends StatelessWidget {
  final Map<String, dynamic> session;
  const _SessionTile({required this.session});

  @override
  Widget build(BuildContext context) {
    final account = session['account_id'] as String? ?? '?';
    final likes   = session['likes']          as int? ?? 0;
    final follows = session['follows']        as int? ?? 0;
    final videos  = session['videos_watched'] as int? ?? 0;
    // Fix: native returns Long (ms since epoch), handle both Long and String
    final startedRaw = session['started_at'];
    final DateTime? startedDt = startedRaw is int
        ? DateTime.fromMillisecondsSinceEpoch(startedRaw)
        : startedRaw is String
            ? DateTime.tryParse(startedRaw)?.toLocal()
            : null;
    final timeStr = startedDt != null
        ? DateFormat('dd/MM HH:mm').format(startedDt)
        : '—';

    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('@$account', style: const TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  Text(timeStr, style: const TextStyle(color: Color(0xFF6B7280), fontSize: 12)),
                ],
              ),
            ),
            _SessionStat(icon: Icons.favorite_rounded,    color: const Color(0xFFEC4899), value: likes),
            _SessionStat(icon: Icons.person_add_rounded,  color: const Color(0xFF10B981), value: follows),
            _SessionStat(icon: Icons.play_circle_rounded, color: const Color(0xFF6C63FF), value: videos),
          ],
        ),
      ),
    );
  }
}

class _SessionStat extends StatelessWidget {
  final IconData icon;
  final Color color;
  final int value;
  const _SessionStat({required this.icon, required this.color, required this.value});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(left: 14),
    child: Column(
      children: [
        Icon(icon, color: color, size: 16),
        const SizedBox(height: 2),
        Text('$value', style: TextStyle(color: color, fontSize: 13, fontWeight: FontWeight.w600)),
      ],
    ),
  );
}

// ── Shared ────────────────────────────────────────────────────

class _SectionTitle extends StatelessWidget {
  final String title;
  const _SectionTitle({required this.title});
  @override
  Widget build(BuildContext context) => Text(title,
      style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15));
}

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../services/providers.dart';

// ════════════════════════════════════════════════════════════════
//  WsMonitorScreen — Phase 3
//  Hiển thị info WS server đang chạy trên thiết bị + test connect
// ════════════════════════════════════════════════════════════════

class WsMonitorScreen extends ConsumerStatefulWidget {
  const WsMonitorScreen({super.key});
  @override
  ConsumerState<WsMonitorScreen> createState() => _WsMonitorScreenState();
}

class _WsMonitorScreenState extends ConsumerState<WsMonitorScreen> {
  static const _ch = MethodChannel('com.atpro/control');

  Map<String, dynamic>? _serverInfo;
  bool _loading     = true;
  final List<String> _receivedMessages = [];
  WebSocketChannel? _testChannel;
  bool _testConnected = false;

  @override
  void initState() {
    super.initState();
    _loadServerInfo();
  }

  @override
  void dispose() {
    _testChannel?.sink.close();
    super.dispose();
  }

  Future<void> _loadServerInfo() async {
    setState(() => _loading = true);
    try {
      final info = await _ch.invokeMethod<Map>('getWsServerInfo');
      setState(() {
        _serverInfo = info != null ? Map<String, dynamic>.from(info) : null;
        _loading    = false;
      });
    } catch (_) {
      setState(() => _loading = false);
    }
  }

  void _testConnect() {
    final url = _serverInfo?['url'] as String?;
    if (url == null) return;
    try {
      _testChannel = WebSocketChannel.connect(Uri.parse(url));
      setState(() { _testConnected = true; _receivedMessages.clear(); });
      _testChannel!.stream.listen(
        (msg) => setState(() => _receivedMessages.insert(0, msg.toString())),
        onDone: () => setState(() => _testConnected = false),
        onError: (_) => setState(() => _testConnected = false),
      );
    } catch (e) {
      _showSnack('Kết nối thất bại: $e', error: true);
    }
  }

  void _disconnect() {
    _testChannel?.sink.close();
    setState(() { _testConnected = false; _testChannel = null; });
  }

  @override
  Widget build(BuildContext context) {
    final wsEvent = ref.watch(wsServerProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Kết nối LAN'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh_rounded), onPressed: _loadServerInfo),
          const SizedBox(width: 4),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [

                // Server status card
                _serverInfo == null
                    ? _NoServer(onRetry: _loadServerInfo)
                    : _ServerInfoCard(info: _serverInfo!),
                const SizedBox(height: 16),

                // WS event stream
                wsEvent.whenData((e) => _EventBanner(event: e)).value ??
                    const SizedBox.shrink(),

                // Test connection
                if (_serverInfo != null) ...[
                  const SizedBox(height: 4),
                  _TestConnectionCard(
                    url:         _serverInfo!['url'] as String? ?? '',
                    connected:   _testConnected,
                    messages:    _receivedMessages,
                    onConnect:   _testConnect,
                    onDisconnect: _disconnect,
                    onSend: (msg) {
                      _testChannel?.sink.add(msg);
                    },
                  ),
                ],
                const SizedBox(height: 16),

                // Usage guide
                const _UsageGuide(),
              ],
            ),
    );
  }

  void _showSnack(String msg, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: error ? const Color(0xFFEF4444) : const Color(0xFF10B981),
    ));
  }
}

// ── Widgets ───────────────────────────────────────────────────

class _ServerInfoCard extends StatelessWidget {
  final Map<String, dynamic> info;
  const _ServerInfoCard({required this.info});

  @override
  Widget build(BuildContext context) {
    final ip      = info['ip']      as String? ?? '—';
    final port    = info['port']    as int?    ?? 8765;
    final clients = info['clients'] as int?    ?? 0;
    final url     = info['url']     as String? ?? '';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 10, height: 10,
                  decoration: const BoxDecoration(
                    color: Color(0xFF10B981), shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 8),
                const Text('Server đang chạy',
                    style: TextStyle(color: Color(0xFF10B981), fontWeight: FontWeight.w600)),
                const Spacer(),
                Text('$clients client${clients != 1 ? "s" : ""}',
                    style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 13)),
              ],
            ),
            const SizedBox(height: 16),
            _InfoRow(label: 'IP',    value: ip),
            _InfoRow(label: 'Port',  value: '$port'),
            _InfoRow(label: 'URL',   value: url, monospace: true),
            const SizedBox(height: 12),
            // Copy URL button
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: url));
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Đã copy URL!'), duration: Duration(seconds: 1)),
                  );
                },
                icon:  const Icon(Icons.copy_rounded, size: 16),
                label: const Text('Copy WS URL'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: const Color(0xFF6C63FF),
                  side: const BorderSide(color: Color(0xFF6C63FF), width: 0.8),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;
  final bool monospace;
  const _InfoRow({required this.label, required this.value, this.monospace = false});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Row(
      children: [
        SizedBox(
          width: 40,
          child: Text(label, style: const TextStyle(color: Color(0xFF6B7280), fontSize: 13)),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(value,
              style: TextStyle(
                fontSize: 13,
                fontFamily: monospace ? 'monospace' : null,
                color: Colors.white,
              )),
        ),
      ],
    ),
  );
}

class _EventBanner extends StatelessWidget {
  final Map<String, dynamic> event;
  const _EventBanner({required this.event});
  @override
  Widget build(BuildContext context) {
    final status = event['status'] as String?;
    if (status == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: const Color(0xFF374151),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Text('Server event: status=$status',
            style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 12, fontFamily: 'monospace')),
      ),
    );
  }
}

class _TestConnectionCard extends StatefulWidget {
  final String url;
  final bool connected;
  final List<String> messages;
  final VoidCallback onConnect;
  final VoidCallback onDisconnect;
  final void Function(String) onSend;
  const _TestConnectionCard({
    required this.url, required this.connected, required this.messages,
    required this.onConnect, required this.onDisconnect, required this.onSend,
  });
  @override
  State<_TestConnectionCard> createState() => _TestConnectionCardState();
}

class _TestConnectionCardState extends State<_TestConnectionCard> {
  final _ctrl = TextEditingController(text: '{"type":"ping"}');
  @override
  void dispose() { _ctrl.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.cable_rounded, color: Color(0xFF6C63FF), size: 18),
              const SizedBox(width: 8),
              const Text('Kiểm tra kết nối', style: TextStyle(fontWeight: FontWeight.w600)),
              const Spacer(),
              FilledButton.tonal(
                onPressed: widget.connected ? widget.onDisconnect : widget.onConnect,
                style: FilledButton.styleFrom(
                  backgroundColor: widget.connected
                      ? const Color(0xFFEF4444).withOpacity(0.15)
                      : const Color(0xFF10B981).withOpacity(0.15),
                  foregroundColor: widget.connected
                      ? const Color(0xFFEF4444)
                      : const Color(0xFF10B981),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                ),
                child: Text(widget.connected ? 'Ngắt kết nối' : 'Kết nối'),
              ),
            ],
          ),
          if (widget.connected) ...[
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _ctrl,
                    decoration: const InputDecoration(
                      hintText: '{"type":"ping"}',
                      isDense: true,
                      contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                      border: OutlineInputBorder(),
                    ),
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: () => widget.onSend(_ctrl.text),
                  icon: const Icon(Icons.send_rounded, size: 18),
                  style: IconButton.styleFrom(backgroundColor: const Color(0xFF6C63FF)),
                ),
              ],
            ),
            const SizedBox(height: 12),
            // Messages log
            Container(
              height: 160,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: const Color(0xFF0D0D14),
                borderRadius: BorderRadius.circular(8),
              ),
              child: widget.messages.isEmpty
                  ? const Center(
                      child: Text('Chờ messages...',
                          style: TextStyle(color: Color(0xFF4B5563), fontSize: 12)))
                  : ListView.builder(
                      itemCount: widget.messages.length,
                      itemBuilder: (_, i) => Padding(
                        padding: const EdgeInsets.only(bottom: 4),
                        child: Text(widget.messages[i],
                            style: const TextStyle(
                              color: Color(0xFF10B981),
                              fontSize: 11,
                              fontFamily: 'monospace',
                            )),
                      ),
                    ),
            ),
          ],
        ],
      ),
    ),
  );
}

class _UsageGuide extends StatelessWidget {
  const _UsageGuide();
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.help_outline_rounded, color: Color(0xFF6B7280), size: 18),
              SizedBox(width: 8),
              Text('Cách dùng Multi-Device', style: TextStyle(fontWeight: FontWeight.w600)),
            ],
          ),
          const SizedBox(height: 12),
          ...[
            '1. Kết nối tất cả thiết bị vào cùng 1 mạng WiFi',
            '2. Copy WS URL từ thiết bị "master"',
            '3. Các thiết bị khác mở màn hình Devices → nhập URL',
            '4. Dashboard sẽ hiển thị realtime status của tất cả thiết bị',
            '5. Có thể điều khiển từ xa: startFarm, stopFarm, pauseFarm',
          ].map((s) => Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Text(s, style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 12, height: 1.4)),
          )),
        ],
      ),
    ),
  );
}

class _NoServer extends StatelessWidget {
  final VoidCallback onRetry;
  const _NoServer({required this.onRetry});
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        children: [
          const Icon(Icons.wifi_off_rounded, size: 48, color: Color(0xFF374151)),
          const SizedBox(height: 12),
          const Text('WS Server chưa khởi động',
              style: TextStyle(color: Color(0xFF6B7280))),
          const SizedBox(height: 12),
          OutlinedButton(onPressed: onRetry, child: const Text('Thử lại')),
        ],
      ),
    ),
  );
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'dart:convert';

// ════════════════════════════════════════════════════════════════
//  MultiDeviceScreen — Phase 3 (LAN WebSocket, no Supabase)
//  Kết nối tới WS server của thiết bị khác trên cùng mạng LAN
// ════════════════════════════════════════════════════════════════

class _DeviceInfo {
  final String name;
  final String ip;
  final bool isFarming;
  final String? currentAccount;
  final int accountIndex;
  final int accountTotal;
  final int sessionLikes;
  final int sessionFollows;
  final int sessionVideos;
  final DateTime lastSeen;

  _DeviceInfo({
    required this.name, required this.ip, required this.isFarming,
    this.currentAccount, this.accountIndex = 0, this.accountTotal = 0,
    this.sessionLikes = 0, this.sessionFollows = 0, this.sessionVideos = 0,
    required this.lastSeen,
  });


}

class MultiDeviceScreen extends StatefulWidget {
  const MultiDeviceScreen({super.key});
  @override
  State<MultiDeviceScreen> createState() => _MultiDeviceScreenState();
}

class _MultiDeviceScreenState extends State<MultiDeviceScreen> {
  static const _ch = MethodChannel('com.atpro/control');
  final _urlCtrl = TextEditingController();
  WebSocketChannel? _channel;
  final Map<String, _DeviceInfo> _devices = {};
  String? _connectedUrl;
  bool _connecting = false;
  String? _localUrl;

  @override
  void initState() { super.initState(); _loadLocalInfo(); }

  @override
  void dispose() { _channel?.sink.close(); _urlCtrl.dispose(); super.dispose(); }

  Future<void> _loadLocalInfo() async {
    try {
      final info = await _ch.invokeMethod<Map>('getWsServerInfo');
      setState(() => _localUrl = info?['url'] as String?);
    } catch (_) {}
  }

  void _connect(String url) {
    if (_connecting) return;
    final wsUrl = url.trim();
    if (wsUrl.isEmpty) return;
    setState(() { _connecting = true; });
    try {
      _channel?.sink.close();
      _channel = WebSocketChannel.connect(Uri.parse(wsUrl));
      _connectedUrl = wsUrl;
      _channel!.stream.listen(
        _onMessage,
        onDone: () => setState(() { _connectedUrl = null; _connecting = false; }),
        onError: (e) {
          setState(() { _connectedUrl = null; _connecting = false; });
          _showSnack('Lỗi: $e', error: true);
        },
      );
      setState(() => _connecting = false);
    } catch (e) {
      setState(() { _connecting = false; });
      _showSnack('Không kết nối được: $e', error: true);
    }
  }

  void _onMessage(dynamic raw) {
    try {
      // Fix 1: server gửi flat JSON — type + data fields cùng level
      // {"type":"liveStats","likes":5,"follows":2,"videos":10,...}
      final json = jsonDecode(raw as String) as Map<String, dynamic>;
      final type = json['type'] as String?;
      final ip   = _connectedUrl?.split('/')[2].split(':')[0] ?? 'unknown';

      switch (type) {
        case 'welcome':
          // Device info từ welcome message
          setState(() {
            _devices[ip] = _DeviceInfo(
              name:     json['device'] as String? ?? ip,
              ip:       ip,
              isFarming: false,
              lastSeen: DateTime.now(),
            );
          });
          break;

        case 'liveStats':
          setState(() {
            final existing = _devices[ip];
            _devices[ip] = _DeviceInfo(
              name:           existing?.name ?? ip,
              ip:             ip,
              isFarming:      true,
              currentAccount: json['account'] as String?,
              accountIndex:   json['index']   as int? ?? 0,
              accountTotal:   json['total']   as int? ?? 0,
              sessionLikes:   json['likes']   as int? ?? 0,
              sessionFollows: json['follows'] as int? ?? 0,
              sessionVideos:  json['videos']  as int? ?? 0,
              lastSeen:       DateTime.now(),
            );
          });
          break;

        case 'farmStatus':
          final status = json['status'] as String?;
          setState(() {
            final existing = _devices[ip];
            if (existing != null) {
              _devices[ip] = _DeviceInfo(
                name:      existing.name,
                ip:        ip,
                isFarming: status == 'started',
                lastSeen:  DateTime.now(),
              );
            }
          });
          break;

        case 'currentAccount':
          setState(() {
            final existing = _devices[ip];
            if (existing != null) {
              _devices[ip] = _DeviceInfo(
                name:           existing.name,
                ip:             ip,
                isFarming:      existing.isFarming,
                currentAccount: json['account'] as String?,
                accountIndex:   json['index']   as int? ?? 0,
                accountTotal:   json['total']   as int? ?? 0,
                sessionLikes:   existing.sessionLikes,
                sessionFollows: existing.sessionFollows,
                sessionVideos:  existing.sessionVideos,
                lastSeen:       DateTime.now(),
              );
            }
          });
          break;
      }
    } catch (e) {
      debugPrint('_onMessage parse error: \$e');
    }
  }

  void _sendCommand(String cmd) {
    _channel?.sink.add(jsonEncode({'type': cmd}));
  }

  @override
  Widget build(BuildContext context) {
    final isConnected = _connectedUrl != null;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Đa thiết bị'),
        actions: [
          if (isConnected)
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Row(
                children: [
                  Container(width: 8, height: 8,
                      decoration: const BoxDecoration(color: Color(0xFF10B981), shape: BoxShape.circle)),
                  const SizedBox(width: 6),
                  const Text('Mạng LAN', style: TextStyle(color: Color(0xFF10B981), fontSize: 12)),
                ],
              ),
            ),
          const SizedBox(width: 4),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // This device info
          if (_localUrl != null) ...[
            _LocalServerCard(url: _localUrl!),
            const SizedBox(height: 16),
          ],

          // Connect to remote
          _ConnectCard(
            ctrl:        _urlCtrl,
            connecting:  _connecting,
            connected:   isConnected,
            connectedUrl: _connectedUrl,
            onConnect:   () => _connect(_urlCtrl.text),
            onDisconnect: () {
              _channel?.sink.close();
              setState(() { _connectedUrl = null; _devices.clear(); });
            },
          ),
          const SizedBox(height: 16),

          // Remote devices
          if (_devices.isNotEmpty) ...[
            const _SectionLabel('📱 Thiết bị đã kết nối'),
            const SizedBox(height: 10),
            ..._devices.values.map((d) => _DeviceCard(
              device: d,
              onStart:  () => _sendCommand('startFarm'),
              onStop:   () => _sendCommand('stopFarm'),
              onPause:  () => _sendCommand('pauseFarm'),
            )),
          ] else if (isConnected) ...[
            const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Column(children: [
                  CircularProgressIndicator(),
                  SizedBox(height: 12),
                  Text('Đang chờ dữ liệu từ thiết bị...',
                      style: TextStyle(color: Color(0xFF6B7280))),
                ]),
              ),
            ),
          ],
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

class _LocalServerCard extends StatelessWidget {
  final String url;
  const _LocalServerCard({required this.url});
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(children: [
            Icon(Icons.router_rounded, color: Color(0xFF6C63FF), size: 18),
            SizedBox(width: 8),
            Text('Server này', style: TextStyle(fontWeight: FontWeight.w600)),
          ]),
          const SizedBox(height: 10),
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(color: const Color(0xFF0D0D14), borderRadius: BorderRadius.circular(8)),
            child: Row(
              children: [
                Expanded(child: Text(url,
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12, color: Color(0xFF10B981)))),
                IconButton(
                  icon: const Icon(Icons.copy_rounded, size: 16, color: Color(0xFF6B7280)),
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: url));
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Copied!'), duration: Duration(seconds: 1)));
                  },
                  padding: EdgeInsets.zero, constraints: const BoxConstraints(),
                ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          const Text('Chia sẻ URL này cho thiết bị khác để kết nối',
              style: TextStyle(color: Color(0xFF6B7280), fontSize: 11)),
        ],
      ),
    ),
  );
}

class _ConnectCard extends StatelessWidget {
  final TextEditingController ctrl;
  final bool connecting, connected;
  final String? connectedUrl;
  final VoidCallback onConnect, onDisconnect;
  const _ConnectCard({
    required this.ctrl, required this.connecting, required this.connected,
    this.connectedUrl, required this.onConnect, required this.onDisconnect,
  });
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(children: [
            Icon(Icons.link_rounded, color: Color(0xFF6C63FF), size: 18),
            SizedBox(width: 8),
            Text('Kết nối thiết bị khác', style: TextStyle(fontWeight: FontWeight.w600)),
          ]),
          const SizedBox(height: 12),
          if (!connected) ...[
            TextField(
              controller: ctrl,
              decoration: const InputDecoration(
                hintText: 'ws://192.168.1.x:8765/ws',
                hintStyle: TextStyle(fontFamily: 'monospace', fontSize: 12),
                prefixIcon: Icon(Icons.wifi_rounded, size: 18),
                border: OutlineInputBorder(), isDense: true,
              ),
              style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: connecting ? null : onConnect,
                style: FilledButton.styleFrom(backgroundColor: const Color(0xFF6C63FF),
                    padding: const EdgeInsets.symmetric(vertical: 12)),
                child: connecting
                    ? const SizedBox(width: 18, height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : const Text('Kết nối'),
              ),
            ),
          ] else ...[
            Row(children: [
              Container(width: 8, height: 8,
                  decoration: const BoxDecoration(color: Color(0xFF10B981), shape: BoxShape.circle)),
              const SizedBox(width: 8),
              Expanded(child: Text(connectedUrl ?? '',
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 11, color: Color(0xFF10B981)))),
              TextButton(onPressed: onDisconnect, child: const Text('Ngắt',
                  style: TextStyle(color: Color(0xFFEF4444)))),
            ]),
          ],
        ],
      ),
    ),
  );
}

class _DeviceCard extends StatelessWidget {
  final _DeviceInfo device;
  final VoidCallback onStart, onStop, onPause;
  const _DeviceCard({required this.device, required this.onStart, required this.onStop, required this.onPause});
  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 10),
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Container(
              width: 40, height: 40,
              decoration: BoxDecoration(
                color: (device.isFarming ? const Color(0xFF10B981) : const Color(0xFF6B7280)).withOpacity(0.15),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(Icons.smartphone_rounded,
                  color: device.isFarming ? const Color(0xFF10B981) : const Color(0xFF6B7280), size: 22),
            ),
            const SizedBox(width: 12),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(device.name, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
              Text(device.ip,   style: const TextStyle(color: Color(0xFF6B7280), fontSize: 11, fontFamily: 'monospace')),
            ])),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: (device.isFarming ? const Color(0xFF10B981) : const Color(0xFF6B7280)).withOpacity(0.15),
                borderRadius: BorderRadius.circular(20),
              ),
              child: Text(device.isFarming ? '● FARMING' : '● IDLE',
                  style: TextStyle(
                    color: device.isFarming ? const Color(0xFF10B981) : const Color(0xFF6B7280),
                    fontSize: 10, fontWeight: FontWeight.w700,
                  )),
            ),
          ]),
          if (device.isFarming && device.currentAccount != null) ...[
            const SizedBox(height: 12),
            Row(children: [
              const Icon(Icons.person_rounded, size: 14, color: Color(0xFF9CA3AF)),
              const SizedBox(width: 4),
              Text('@${device.currentAccount}', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500)),
              const Spacer(),
              Text('${device.accountIndex}/${device.accountTotal}',
                  style: const TextStyle(color: Color(0xFF6B7280), fontSize: 12)),
            ]),
            const SizedBox(height: 6),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: device.accountTotal > 0 ? device.accountIndex / device.accountTotal : 0,
                backgroundColor: const Color(0xFF374151), color: const Color(0xFF6C63FF), minHeight: 4,
              ),
            ),
            const SizedBox(height: 10),
            Row(mainAxisAlignment: MainAxisAlignment.spaceAround, children: [
              _Stat(Icons.favorite_rounded, const Color(0xFFEC4899), 'Likes', device.sessionLikes),
              _Stat(Icons.person_add_rounded, const Color(0xFF10B981), 'Follows', device.sessionFollows),
              _Stat(Icons.play_circle_rounded, const Color(0xFF6C63FF), 'Videos', device.sessionVideos),
            ]),
          ],
          const SizedBox(height: 10),
          // Remote control row
          Row(children: [
            _RemoteBtn(Icons.stop_rounded,     const Color(0xFFEF4444), 'Stop',  onStop),
            const SizedBox(width: 6),
            _RemoteBtn(Icons.pause_rounded,    const Color(0xFFF59E0B), 'Pause', onPause),
            const SizedBox(width: 6),
            _RemoteBtn(Icons.play_arrow_rounded, const Color(0xFF10B981), 'Start', onStart),
          ]),
        ],
      ),
    ),
  );
}

class _Stat extends StatelessWidget {
  final IconData icon; final Color color; final String label; final int value;
  const _Stat(this.icon, this.color, this.label, this.value);
  @override
  Widget build(BuildContext context) => Row(children: [
    Icon(icon, color: color, size: 14), const SizedBox(width: 4),
    Text('$value', style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 13)),
    const SizedBox(width: 3),
    Text(label, style: const TextStyle(color: Color(0xFF6B7280), fontSize: 11)),
  ]);
}

class _RemoteBtn extends StatelessWidget {
  final IconData icon; final Color color; final String label; final VoidCallback onTap;
  const _RemoteBtn(this.icon, this.color, this.label, this.onTap);
  @override
  Widget build(BuildContext context) => Expanded(
    child: OutlinedButton.icon(
      onPressed: onTap,
      icon:  Icon(icon, size: 14, color: color),
      label: Text(label, style: TextStyle(color: color, fontSize: 12)),
      style: OutlinedButton.styleFrom(
        padding: const EdgeInsets.symmetric(vertical: 8),
        side: BorderSide(color: color.withOpacity(0.4)),
      ),
    ),
  );
}

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);
  @override
  Widget build(BuildContext context) => Text(text,
      style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13, color: Color(0xFF9CA3AF)));
}

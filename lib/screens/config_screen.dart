import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/at_pro_bridge.dart';

// ════════════════════════════════════════════════════════════════
//  Config — Tab-based: chia thành 3 nhóm nhỏ thay vì 1 cột dài
//  Tab 1: Thời gian  Tab 2: Hành động  Tab 3: Thông báo
// ════════════════════════════════════════════════════════════════

class ConfigScreen extends ConsumerStatefulWidget {
  const ConfigScreen({super.key});
  @override
  ConsumerState<ConfigScreen> createState() => _ConfigScreenState();
}

class _ConfigScreenState extends ConsumerState<ConfigScreen>
    with SingleTickerProviderStateMixin {

  final _bridge = AtProBridge();
  late final TabController _tab;
  bool _loading = true, _saving = false;

  // Timing
  int    minutesPerAccount     = 5;
  double watchMin              = 3.0;
  double watchMax              = 8.0;
  bool   enableRest            = false;
  int    restMinutes           = 2;
  int    maxBackAttempts       = 5;

  // Actions
  double likeRate              = 0.30;
  double followRate            = 0.15;
  bool   skipLive              = true;
  bool   verifyAccount         = true;

  // Notifications
  String telegramToken         = '';
  String telegramChatId        = '';
  String discordWebhook        = '';

  @override
  void initState() {
    super.initState();
    _tab = TabController(length: 3, vsync: this);
    _load();
  }

  @override
  void dispose() { _tab.dispose(); super.dispose(); }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      minutesPerAccount = int.tryParse(    await _bridge.loadConfig('minutes_per_account') ?? '') ?? minutesPerAccount;
      watchMin          = double.tryParse( await _bridge.loadConfig('watch_time_min')       ?? '') ?? watchMin;
      watchMax          = double.tryParse( await _bridge.loadConfig('watch_time_max')       ?? '') ?? watchMax;
      enableRest        = (await _bridge.loadConfig('enable_rest')) == 'true';
      restMinutes       = int.tryParse(    await _bridge.loadConfig('rest_minutes')          ?? '') ?? restMinutes;
      maxBackAttempts   = int.tryParse(    await _bridge.loadConfig('max_back_attempts')     ?? '') ?? maxBackAttempts;
      likeRate          = double.tryParse( await _bridge.loadConfig('like_rate')             ?? '') ?? likeRate;
      followRate        = double.tryParse( await _bridge.loadConfig('follow_rate')           ?? '') ?? followRate;
      skipLive          = (await _bridge.loadConfig('skip_live'))      != 'false';
      verifyAccount     = (await _bridge.loadConfig('verify_account')) != 'false';
      telegramToken     = await _bridge.loadConfig('telegram_token')   ?? '';
      telegramChatId    = await _bridge.loadConfig('telegram_chat_id') ?? '';
      discordWebhook    = await _bridge.loadConfig('discord_webhook')  ?? '';
    } catch (_) {}
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    await Future.wait([
      _bridge.saveConfig('minutes_per_account', '$minutesPerAccount'),
      _bridge.saveConfig('watch_time_min',      '$watchMin'),
      _bridge.saveConfig('watch_time_max',      '$watchMax'),
      _bridge.saveConfig('enable_rest',         '$enableRest'),
      _bridge.saveConfig('rest_minutes',        '$restMinutes'),
      _bridge.saveConfig('max_back_attempts',   '$maxBackAttempts'),
      _bridge.saveConfig('like_rate',           '$likeRate'),
      _bridge.saveConfig('follow_rate',         '$followRate'),
      _bridge.saveConfig('skip_live',           '$skipLive'),
      _bridge.saveConfig('verify_account',      '$verifyAccount'),
      _bridge.saveConfig('telegram_token',      telegramToken),
      _bridge.saveConfig('telegram_chat_id',    telegramChatId),
      _bridge.saveConfig('discord_webhook',     discordWebhook),
    ]);
    setState(() => _saving = false);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content:         Text('Đã lưu'),
          backgroundColor: Color(0xFF10B981),
          duration:        Duration(seconds: 2),
          behavior:        SnackBarBehavior.floating,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Scaffold(body: Center(child: CircularProgressIndicator()));

    return Scaffold(
      backgroundColor: const Color(0xFF0D0D14),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0D0D14),
        title: const Text('Cài đặt', style: TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          TextButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox(width: 18, height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF6C63FF)))
                : const Text('Lưu', style: TextStyle(
                    color: Color(0xFF6C63FF), fontWeight: FontWeight.w700)),
          ),
          const SizedBox(width: 8),
        ],
        bottom: TabBar(
          controller:           _tab,
          labelColor:           const Color(0xFF6C63FF),
          unselectedLabelColor: const Color(0xFF6B7280),
          indicatorColor:       const Color(0xFF6C63FF),
          indicatorSize:        TabBarIndicatorSize.label,
          tabs: const [
            Tab(text: 'Thời gian'),
            Tab(text: 'Hành động'),
            Tab(text: 'Thông báo'),
          ],
        ),
      ),

      body: TabBarView(
        controller: _tab,
        children: [

          // ── Tab 1: Timing ─────────────────────────────────
          _TabPage(children: [
            _SliderRow(
              label:    'Thời gian farm mỗi tài khoản',
              value:    minutesPerAccount.toDouble(),
              min: 1, max: 60, divisions: 59,
              display:  '$minutesPerAccount phút',
              onChanged: (v) => setState(() => minutesPerAccount = v.round()),
            ),
            _Divider(),
            _SliderRow(
              label:    'Xem video tối thiểu',
              value:    watchMin,
              min: 1, max: 15, divisions: 28,
              display:  '${watchMin.toStringAsFixed(1)}s',
              onChanged: (v) => setState(() => watchMin = v),
            ),
            _SliderRow(
              label:    'Xem video tối đa',
              value:    watchMax,
              min: 2, max: 30, divisions: 56,
              display:  '${watchMax.toStringAsFixed(1)}s',
              onChanged: (v) => setState(() => watchMax = v),
            ),
            _Divider(),
            _SwitchRow(
              label:    'Nghỉ giữa các tài khoản',
              value:    enableRest,
              onChanged: (v) => setState(() => enableRest = v),
            ),
            if (enableRest)
              _SliderRow(
                label:    'Thời gian nghỉ',
                value:    restMinutes.toDouble(),
                min: 1, max: 30, divisions: 29,
                display:  '$restMinutes phút',
                onChanged: (v) => setState(() => restMinutes = v.round()),
              ),
            _Divider(),
            _StepperRow(
              label:    'Số lần nhấn Back để khôi phục',
              value:    maxBackAttempts,
              min: 1, max: 20,
              onChanged: (v) => setState(() => maxBackAttempts = v),
            ),
          ]),

          // ── Tab 2: Actions ────────────────────────────────
          _TabPage(children: [
            _SliderRow(
              label:    'Tỉ lệ thích video',
              value:    likeRate,
              min: 0, max: 1, divisions: 20,
              display:  '${(likeRate * 100).toInt()}%',
              onChanged: (v) => setState(() => likeRate = v),
            ),
            _SliderRow(
              label:    'Tỉ lệ theo dõi',
              value:    followRate,
              min: 0, max: 1, divisions: 20,
              display:  '${(followRate * 100).toInt()}%',
              onChanged: (v) => setState(() => followRate = v),
            ),
            _Divider(),
            _SwitchRow(
              label:    'Bỏ qua video trực tiếp',
              subtitle: 'Tự động vuốt qua khi gặp livestream',
              value:    skipLive,
              onChanged: (v) => setState(() => skipLive = v),
            ),
            _SwitchRow(
              label:    'Xác nhận tài khoản sau khi chuyển',
              subtitle: 'Vào profile kiểm tra đúng tài khoản',
              value:    verifyAccount,
              onChanged: (v) => setState(() => verifyAccount = v),
            ),
          ]),

          // ── Tab 3: Notifications ──────────────────────────
          _TabPage(children: [
            _SectionLabel('Telegram'),
            _TextField(
              label:     'Bot Token',
              value:     telegramToken,
              hint:      '123456789:ABCdef...',
              obscure:   true,
              onChanged: (v) => telegramToken = v,
            ),
            _TextField(
              label:     'Chat ID',
              value:     telegramChatId,
              hint:      '-100...',
              onChanged: (v) => telegramChatId = v,
            ),
            _Divider(),
            _SectionLabel('Discord'),
            _TextField(
              label:     'Webhook URL',
              value:     discordWebhook,
              hint:      'https://discord.com/api/webhooks/...',
              obscure:   true,
              onChanged: (v) => discordWebhook = v,
            ),
            const SizedBox(height: 8),
            Container(
              padding:    const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color:        const Color(0xFF1A1A2E),
                borderRadius: BorderRadius.circular(10),
              ),
              child: const Row(children: [
                Icon(Icons.info_outline_rounded, color: Color(0xFF6B7280), size: 16),
                SizedBox(width: 8),
                Expanded(child: Text(
                  'Thông báo được gửi khi bắt đầu farm, hoàn thành phiên, hoặc phát hiện checkpoint.',
                  style: TextStyle(color: Color(0xFF6B7280), fontSize: 12, height: 1.5),
                )),
              ]),
            ),
          ]),

        ],
      ),
    );
  }
}

// ── Shared Config Widgets ─────────────────────────────────────

class _TabPage extends StatelessWidget {
  final List<Widget> children;
  const _TabPage({required this.children});
  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
    children: children,
  );
}

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Text(text, style: const TextStyle(
      color: Color(0xFF6C63FF), fontWeight: FontWeight.w700, fontSize: 13, letterSpacing: 0.5)),
  );
}

class _Divider extends StatelessWidget {
  @override
  Widget build(BuildContext context) =>
      const Divider(color: Color(0xFF1F2937), height: 28);
}

class _SliderRow extends StatelessWidget {
  final String label, display;
  final double value, min, max;
  final int divisions;
  final ValueChanged<double> onChanged;
  const _SliderRow({required this.label, required this.value, required this.min,
    required this.max, required this.divisions, required this.display, required this.onChanged});

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 4),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
        Text(label, style: const TextStyle(fontSize: 14, color: Color(0xFFE5E7EB))),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
          decoration: BoxDecoration(
            color:        const Color(0xFF6C63FF).withOpacity(0.15),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Text(display, style: const TextStyle(
              color: Color(0xFF6C63FF), fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ]),
      SliderTheme(
        data: SliderTheme.of(context).copyWith(
          activeTrackColor:   const Color(0xFF6C63FF),
          thumbColor:         const Color(0xFF6C63FF),
          inactiveTrackColor: const Color(0xFF374151),
          overlayColor:       const Color(0xFF6C63FF).withOpacity(0.1),
          trackHeight:        3,
          thumbShape:         const RoundSliderThumbShape(enabledThumbRadius: 7),
        ),
        child: Slider(value: value.clamp(min, max), min: min, max: max,
            divisions: divisions, onChanged: onChanged),
      ),
    ]),
  );
}

class _SwitchRow extends StatelessWidget {
  final String label;
  final String? subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;
  const _SwitchRow({required this.label, this.subtitle, required this.value, required this.onChanged});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Row(children: [
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: const TextStyle(fontSize: 14, color: Color(0xFFE5E7EB))),
        if (subtitle != null)
          Padding(padding: const EdgeInsets.only(top: 2),
            child: Text(subtitle!, style: const TextStyle(fontSize: 12, color: Color(0xFF6B7280)))),
      ])),
      Switch(value: value, onChanged: onChanged, activeColor: const Color(0xFF6C63FF)),
    ]),
  );
}

class _StepperRow extends StatelessWidget {
  final String label;
  final int value, min, max;
  final ValueChanged<int> onChanged;
  const _StepperRow({required this.label, required this.value,
      required this.min, required this.max, required this.onChanged});
  @override
  Widget build(BuildContext context) => Row(children: [
    Expanded(child: Text(label, style: const TextStyle(fontSize: 14, color: Color(0xFFE5E7EB)))),
    _StepBtn(Icons.remove_rounded, value > min ? () => onChanged(value - 1) : null),
    Padding(padding: const EdgeInsets.symmetric(horizontal: 14),
      child: Text('$value', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
    _StepBtn(Icons.add_rounded, value < max ? () => onChanged(value + 1) : null),
  ]);
}

class _StepBtn extends StatelessWidget {
  final IconData icon;
  final VoidCallback? onTap;
  const _StepBtn(this.icon, this.onTap);
  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: onTap,
    child: Container(
      width: 32, height: 32,
      decoration: BoxDecoration(
        color:        onTap != null
            ? const Color(0xFF6C63FF).withOpacity(0.15)
            : const Color(0xFF374151),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Icon(icon, size: 16,
          color: onTap != null ? const Color(0xFF6C63FF) : const Color(0xFF6B7280)),
    ),
  );
}

class _TextField extends StatefulWidget {
  final String label, value, hint;
  final bool obscure;
  final ValueChanged<String> onChanged;
  const _TextField({required this.label, required this.value, this.hint = '',
      this.obscure = false, required this.onChanged});
  @override
  State<_TextField> createState() => _TextFieldState();
}

class _TextFieldState extends State<_TextField> {
  late final _ctrl = TextEditingController(text: widget.value);
  bool _show = false;
  @override void dispose() { _ctrl.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(widget.label, style: const TextStyle(fontSize: 12, color: Color(0xFF9CA3AF))),
      const SizedBox(height: 6),
      TextField(
        controller:  _ctrl,
        obscureText: widget.obscure && !_show,
        onChanged:   widget.onChanged,
        decoration:  InputDecoration(
          hintText:        widget.hint,
          hintStyle:       const TextStyle(color: Color(0xFF4B5563), fontSize: 13),
          filled:          true,
          fillColor:       const Color(0xFF1A1A2E),
          isDense:         true,
          contentPadding:  const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          border:          OutlineInputBorder(
              borderRadius: BorderRadius.circular(10), borderSide: BorderSide.none),
          focusedBorder:   OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide:   const BorderSide(color: Color(0xFF6C63FF))),
          suffixIcon: widget.obscure
              ? IconButton(
                  icon:      Icon(_show ? Icons.visibility_off_rounded : Icons.visibility_rounded,
                      size: 18, color: const Color(0xFF6B7280)),
                  onPressed: () => setState(() => _show = !_show))
              : null,
        ),
        style: const TextStyle(fontSize: 14),
      ),
    ]),
  );
}

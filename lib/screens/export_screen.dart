import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

// ════════════════════════════════════════════════════════════════
//  ExportScreen — Phase 3
//  Export dữ liệu từ Room DB ra CSV, chia sẻ hoặc lưu file
// ════════════════════════════════════════════════════════════════

class ExportScreen extends StatefulWidget {
  const ExportScreen({super.key});
  @override
  State<ExportScreen> createState() => _ExportScreenState();
}

class _ExportScreenState extends State<ExportScreen> {
  static const _ch = MethodChannel('com.atpro/control');

  bool _exportingSessions = false;
  bool _exportingAccounts = false;
  String? _lastExport;

  @override
  Widget build(BuildContext context) {
    final now = DateFormat('dd/MM/yyyy').format(DateTime.now());

    return Scaffold(
      appBar: AppBar(title: const Text('Xuất dữ liệu')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Info banner
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: const Color(0xFF6C63FF).withOpacity(0.08),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0xFF6C63FF).withOpacity(0.25)),
            ),
            child: Row(
              children: [
                const Icon(Icons.info_outline_rounded, color: Color(0xFF6C63FF), size: 18),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Dữ liệu được lưu hoàn toàn local trên thiết bị.\nExport CSV để phân tích ngoài app.',
                    style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 12, height: 1.4),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // Sessions export
          _ExportCard(
            icon:        Icons.history_rounded,
            title:       'Phiên farm',
            subtitle:    'Tất cả lịch sử farm: account, thời gian, likes, follows, videos',
            filename:    'atpro_sessions_$now.csv',
            color:       const Color(0xFF6C63FF),
            isLoading:   _exportingSessions,
            onExport:    _exportSessions,
            onCopy:      () => _copyToClipboard('sessions'),
          ),
          const SizedBox(height: 12),

          // Accounts export
          _ExportCard(
            icon:        Icons.people_rounded,
            title:       'Tài khoản',
            subtitle:    'Danh sách tài khoản: username, status, tổng stats',
            filename:    'atpro_accounts_$now.csv',
            color:       const Color(0xFF10B981),
            isLoading:   _exportingAccounts,
            onExport:    _exportAccounts,
            onCopy:      () => _copyToClipboard('accounts'),
          ),
          const SizedBox(height: 24),

          // Last export info
          if (_lastExport != null) ...[
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFF10B981).withOpacity(0.08),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: const Color(0xFF10B981).withOpacity(0.3)),
              ),
              child: Row(
                children: [
                  const Icon(Icons.check_circle_outline_rounded, color: Color(0xFF10B981), size: 16),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _lastExport!,
                      style: const TextStyle(color: Color(0xFF10B981), fontSize: 12),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _exportSessions() async {
    setState(() => _exportingSessions = true);
    try {
      final csv = await _ch.invokeMethod<String>('exportSessionsCsv') ?? '';
      await _saveAndShare(csv, 'atpro_sessions.csv');
      setState(() => _lastExport = '✅ Sessions exported thành công');
    } catch (e) {
      _showError(e.toString());
    } finally {
      setState(() => _exportingSessions = false);
    }
  }

  Future<void> _exportAccounts() async {
    setState(() => _exportingAccounts = true);
    try {
      final csv = await _ch.invokeMethod<String>('exportAccountsCsv') ?? '';
      await _saveAndShare(csv, 'atpro_accounts.csv');
      setState(() => _lastExport = '✅ Accounts exported thành công');
    } catch (e) {
      _showError(e.toString());
    } finally {
      setState(() => _exportingAccounts = false);
    }
  }

  Future<void> _saveAndShare(String csv, String filename) async {
    final dir  = await getTemporaryDirectory();
    final file = File('${dir.path}/$filename');
    await file.writeAsString(csv, encoding: utf8);
    await Share.shareXFiles(
      [XFile(file.path, mimeType: 'text/csv')],
      subject: 'AT PRO — $filename',
    );
  }

  Future<void> _copyToClipboard(String type) async {
    try {
      final csv = await _ch.invokeMethod<String>(
        type == 'sessions' ? 'exportSessionsCsv' : 'exportAccountsCsv',
      ) ?? '';
      await Clipboard.setData(ClipboardData(text: csv));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Đã copy CSV vào clipboard!'),
            backgroundColor: Color(0xFF10B981),
            duration: Duration(seconds: 2),
          ),
        );
      }
    } catch (e) { _showError(e.toString()); }
  }

  void _showError(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Lỗi: $msg'), backgroundColor: const Color(0xFFEF4444)),
    );
  }
}

class _ExportCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final String filename;
  final Color color;
  final bool isLoading;
  final VoidCallback onExport;
  final VoidCallback onCopy;

  const _ExportCard({
    required this.icon, required this.title, required this.subtitle,
    required this.filename, required this.color, required this.isLoading,
    required this.onExport, required this.onCopy,
  });

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 40, height: 40,
                decoration: BoxDecoration(
                  color: color.withOpacity(0.15),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: color, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                    Text(subtitle, style: const TextStyle(color: Color(0xFF9CA3AF), fontSize: 12)),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          // Filename chip
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
            decoration: BoxDecoration(
              color: const Color(0xFF0D0D14),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(filename,
                style: const TextStyle(
                  color: Color(0xFF6B7280),
                  fontSize: 11,
                  fontFamily: 'monospace',
                )),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              // Copy button
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: isLoading ? null : onCopy,
                  icon:  const Icon(Icons.copy_rounded, size: 16),
                  label: const Text('Sao chép CSV'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: color,
                    side: BorderSide(color: color.withOpacity(0.5)),
                    padding: const EdgeInsets.symmetric(vertical: 10),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              // Export + Share button
              Expanded(
                flex: 2,
                child: FilledButton.icon(
                  onPressed: isLoading ? null : onExport,
                  icon: isLoading
                      ? const SizedBox(
                          width: 16, height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                        )
                      : const Icon(Icons.share_rounded, size: 16),
                  label: Text(isLoading ? 'Đang xuất...' : 'Xuất & Chia sẻ'),
                  style: FilledButton.styleFrom(
                    backgroundColor: color,
                    padding: const EdgeInsets.symmetric(vertical: 10),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}

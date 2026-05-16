import 'dart:async';
import 'package:flutter/services.dart';

class AtProBridge {
  static const _method = MethodChannel('com.atpro/control');
  static const _events = EventChannel('com.atpro/events');
  static final AtProBridge _i = AtProBridge._();
  factory AtProBridge() => _i;
  AtProBridge._();

  Stream<Map<String, dynamic>> get eventStream =>
      _events.receiveBroadcastStream().map((e) => Map<String, dynamic>.from(e as Map));
  Stream<Map<String, dynamic>> eventsOfType(String t) =>
      eventStream.where((e) => e['type'] == t);

  Stream<Map<String, dynamic>> get logStream     => eventsOfType('log');
  Stream<Map<String, dynamic>> get statsStream   => eventsOfType('liveStats');
  Stream<Map<String, dynamic>> get farmStatus    => eventsOfType('farmStatus');
  Stream<Map<String, dynamic>> get accountStream => eventsOfType('currentAccount');
  Stream<Map<String, dynamic>> get serviceStatus => eventsOfType('serviceStatus');
  Stream<Map<String, dynamic>> get wsServerStream => eventsOfType('wsServer');

  Future<void> startFarm(List<String> accounts) =>
      _method.invokeMethod('startFarm', {'accounts': accounts});
  Future<void> stopFarm()   => _method.invokeMethod('stopFarm');
  Future<void> pauseFarm()  => _method.invokeMethod('pauseFarm');
  Future<void> resumeFarm() => _method.invokeMethod('resumeFarm');

  Future<List<Map<String, dynamic>>> getAccounts() async {
    final r = await _method.invokeMethod<List>('getAccountsFromDb');
    return (r ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }
  Future<void> addAccount(String username) =>
      _method.invokeMethod('addAccount', {'username': username});
  Future<void> deleteAccount(String username) =>
      _method.invokeMethod('deleteAccount', {'username': username});
  Future<void> setCheckpoint(String username, bool checkpoint) =>
      _method.invokeMethod('setCheckpoint', {'username': username, 'checkpoint': checkpoint});

  Future<List<Map<String, dynamic>>> getRecentSessions({int limit = 50}) async {
    final r = await _method.invokeMethod<List>('getRecentSessions', {'limit': limit});
    return (r ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }
  Future<List<Map<String, dynamic>>> getDailyStats({int days = 30}) async {
    final r = await _method.invokeMethod<List>('getDailyStats', {'days': days});
    return (r ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }
  Future<Map<String, dynamic>> getTotals({int days = 30}) async {
    final r = await _method.invokeMethod<Map>('getTotals', {'days': days});
    return r != null ? Map<String, dynamic>.from(r) : {};
  }

  Future<void> saveConfig(String key, String value) =>
      _method.invokeMethod('saveConfig', {'key': key, 'value': value});
  Future<String?> loadConfig(String key) =>
      _method.invokeMethod<String>('loadConfig', {'key': key});

  Future<String> exportSessionsCsv() async =>
      await _method.invokeMethod<String>('exportSessionsCsv') ?? '';
  Future<String> exportAccountsCsv() async =>
      await _method.invokeMethod<String>('exportAccountsCsv') ?? '';

  Future<Map<String, dynamic>> getPermissionStatus() async {
    final r = await _method.invokeMethod<Map>('getPermissionStatus');
    return r != null ? Map<String, dynamic>.from(r) : {};
  }
  Future<void> openAccessibilitySettings() =>
      _method.invokeMethod('openAccessibilitySettings');
  Future<void> openOverlaySettings() =>
      _method.invokeMethod('openOverlaySettings');
  Future<void> openAppSettings() =>
      _method.invokeMethod('openSettings');

  Future<Map<String, dynamic>> getWsServerInfo() async {
    final r = await _method.invokeMethod<Map>('getWsServerInfo');
    return r != null ? Map<String, dynamic>.from(r) : {};
  }
}

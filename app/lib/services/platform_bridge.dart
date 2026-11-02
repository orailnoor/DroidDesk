import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';
import 'package:droiddesk/models/desktop_display.dart';

/// Platform channel bridge to communicate with the Kotlin native layer.
///
/// All heavy work (bootstrap extraction, native pkg install, process management)
/// runs on the Kotlin side. Flutter calls into Kotlin via MethodChannel and
/// receives callbacks.
class DroidDeskPlatform {
  static const _channel = MethodChannel('com.droiddesk/core');

  // Callback handlers (set by the UI layer)
  static Function(double progress, String status)? onDownloadProgress;
  static Function(double progress, String status)? onExtractProgress;
  static Function(double progress, String status)? onInstallProgress;
  static Function(double progress, String status)? onOptionalInstallProgress;
  static Function(double progress, String status)? onPackageOperationProgress;
  static Function(String text)? onPackageOperationLog;
  static Function(String text)? onTerminalOutput;
  static Function(int downloaded, int total)? onUpdateProgress;
  static void Function(DesktopDisplayState)? onDesktopDisplayStateChanged;
  static void Function(FormatException)? onDesktopDisplayError;

  static Future<void> updateStatusBarTheme(bool isLightMode) async {
    try {
      await _channel.invokeMethod('updateStatusBarTheme', {'isLightMode': isLightMode});
    } catch (_) {}
  }

  /// Initialize platform channel listeners
  static void init() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onDesktopDisplayStateChanged':
          try {
            onDesktopDisplayStateChanged?.call(
              DesktopDisplayState.fromMap(desktopDisplayMap(call.arguments)),
            );
          } on FormatException catch (error) {
            debugPrint('Invalid native desktop display state: $error');
            onDesktopDisplayError?.call(error);
          }
          break;
        case 'onDownloadProgress':
          final args = call.arguments as Map;
          onDownloadProgress?.call(
            (args['progress'] as num).toDouble(),
            args['status'] as String,
          );
          break;
        case 'onExtractProgress':
          final args = call.arguments as Map;
          onExtractProgress?.call(
            (args['progress'] as num).toDouble(),
            args['status'] as String,
          );
          break;
        case 'onInstallProgress':
          final args = call.arguments as Map;
          onInstallProgress?.call(
            (args['progress'] as num).toDouble(),
            args['status'] as String,
          );
          break;
        case 'onTerminalOutput':
          final args = call.arguments as Map;
          onTerminalOutput?.call(args['text'] as String);
          break;
        case 'onOptionalInstallProgress':
          final args = call.arguments as Map;
          onOptionalInstallProgress?.call(
            (args['progress'] as num).toDouble(),
            args['status'] as String,
          );
          break;
        case 'onPackageOperationProgress':
          final args = call.arguments as Map;
          onPackageOperationProgress?.call(
            (args['progress'] as num).toDouble(),
            args['status'] as String,
          );
          break;
        case 'onPackageOperationLog':
          final args = call.arguments as Map;
          onPackageOperationLog?.call(args['text'] as String);
          break;
        case 'onUpdateProgress':
          final args = call.arguments as Map;
          onUpdateProgress?.call(
            (args['downloaded'] as num).toInt(),
            (args['total'] as num).toInt(),
          );
          break;
      }
    });
  }

  // ── In-app updates ──

  /// Installed app version: `{versionName, versionCode}`.
  static Future<Map<String, dynamic>> getAppVersion() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('getAppVersion');
    return result == null ? {} : Map<String, dynamic>.from(result);
  }

  /// Whether Android allows this app to install packages ("Install unknown apps").
  static Future<bool> canInstallUpdates() async {
    return await _channel.invokeMethod<bool>('canInstallUpdates') ?? false;
  }

  /// Opens the "Install unknown apps" settings page for DroidDesk.
  static Future<void> openInstallPermission() async {
    await _channel.invokeMethod('openInstallPermission');
  }

  /// Downloads and verifies a release APK. Returns `{ok, path}` or `{ok: false, code, message}`.
  static Future<Map<String, dynamic>> downloadUpdate({
    required String url,
    required int size,
    required String tag,
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'downloadUpdate',
      {'url': url, 'size': size, 'tag': tag},
    );
    return result == null ? {'ok': false, 'code': 'unknown'} : Map<String, dynamic>.from(result);
  }

  static Future<void> cancelUpdateDownload() async {
    await _channel.invokeMethod('cancelUpdateDownload');
  }

  static Future<void> cleanupUpdates() async {
    await _channel.invokeMethod('cleanupUpdates');
  }

  /// Opens Android's package installer for a downloaded update.
  static Future<bool> installUpdate(String path) async {
    return await _channel.invokeMethod<bool>('installUpdate', {'path': path}) ?? false;
  }

  // ── Runtime Status ──

  static Future<Map<String, dynamic>> getRuntimeStatus() async {
    final result = await _channel.invokeMethod('getRuntimeStatus');
    return Map<String, dynamic>.from(result);
  }

  // ── Device Info ──

  static Future<Map<String, dynamic>> getDeviceInfo() async {
    final result = await _channel.invokeMethod('getDeviceInfo');
    return Map<String, dynamic>.from(result);
  }

  // ── Bootstrap ──

  static Future<void> setupBootstrap() async {
    await _channel.invokeMethod('setupBootstrap');
  }

  // ── Native Desktop Environment Installation (non-root fallback) ──

  static Future<bool> installDesktopNative({String de = 'xfce4'}) async {
    final result = await _channel.invokeMethod('installDesktopNative', {
      'de': de,
    });
    return result as bool? ?? false;
  }

  // ── Root / chroot support ──

  static Future<bool> checkRoot() async {
    final result = await _channel.invokeMethod('checkRoot');
    return result as bool? ?? false;
  }

  static Future<void> resetRootCache() async {
    await _channel.invokeMethod('resetRootCache');
  }

  // ── Rootfs Management (chroot mode) ──

  static Future<bool> downloadRootfs(String distro) async {
    return await _channel.invokeMethod<bool>('downloadRootfs', {
          'distro': distro,
        }) ??
        false;
  }

  static Future<bool> extractRootfs() async {
    return await _channel.invokeMethod<bool>('extractRootfs') ?? false;
  }

  static Future<bool> installDesktopEnvironment(String de) async {
    return await _channel.invokeMethod<bool>('installDesktopEnvironment', {
          'de': de,
        }) ??
        false;
  }

  static Future<Map<String, bool>> getOptionalApps() async {
    final result = await _channel.invokeMethod('getOptionalApps');
    return Map<String, bool>.from(result);
  }

  static Future<bool> installOptionalApp(String appId) async {
    return await _channel.invokeMethod<bool>('installOptionalApp', {
          'appId': appId,
        }) ??
        false;
  }

  // ── Native package store ──

  static Future<List<Map<String, dynamic>>> searchNativePackages(
    String query, {
    int limit = 60,
  }) async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'searchNativePackages',
      {'query': query, 'limit': limit},
    );
    return (result ?? const [])
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList();
  }

  static Future<List<Map<String, dynamic>>> getInstalledNativePackages() async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'getInstalledNativePackages',
    );
    return (result ?? const [])
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList();
  }

  static Future<bool> installNativePackage(String packageName) async {
    return await _channel.invokeMethod<bool>('installNativePackage', {
          'package': packageName,
        }) ??
        false;
  }

  /// Installed packages with newer versions; [refresh] runs `apt-get update` first (slow).
  static Future<List<Map<String, dynamic>>> getUpgradablePackages({bool refresh = false}) async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'getUpgradablePackages',
      {'refresh': refresh},
    );
    return (result ?? const [])
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList();
  }

  static Future<bool> upgradeAllPackages() async {
    return await _channel.invokeMethod<bool>('upgradeAllPackages') ?? false;
  }

  /// Linux and device disk usage in bytes: system, home, cache, deviceFree, deviceTotal.
  static Future<Map<String, dynamic>> getStorageInfo() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('getStorageInfo');
    return result == null ? {} : Map<String, dynamic>.from(result);
  }

  /// Deletes downloaded package files; returns bytes freed or -1 on failure.
  static Future<int> cleanPackageCache() async {
    return await _channel.invokeMethod<int>('cleanPackageCache') ?? -1;
  }

  static Future<bool> removeNativePackage(String packageName) async {
    return await _channel.invokeMethod<bool>('removeNativePackage', {
          'package': packageName,
        }) ??
        false;
  }

  static Future<bool> cancelNativePackageOperation() async {
    return await _channel.invokeMethod<bool>('cancelNativePackageOperation') ??
        false;
  }

  // ── Unified desktop integration ──

  static Future<List<Map<String, dynamic>>> searchDesktopItems(
    String query,
  ) async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'searchDesktopItems',
      {'query': query},
    );
    return (result ?? const [])
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList();
  }

  static Future<bool> launchDesktopItem(String kind, String id) async {
    return await _channel.invokeMethod<bool>('launchDesktopItem', {
          'kind': kind,
          'id': id,
        }) ??
        false;
  }

  static Future<List<Map<String, dynamic>>> getAndroidApps() async {
    final result = await _channel.invokeMethod<List<dynamic>>('getAndroidApps');
    return (result ?? const [])
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList();
  }

  /// Launcher icon of an installed Android app as PNG bytes, or null if unavailable.
  static Future<Uint8List?> getAppIcon(String packageName) async {
    return _channel.invokeMethod<Uint8List>('getAppIcon', {'package': packageName});
  }

  /// Real icon for a Linux package: `{'format': 'png'|'svg', 'bytes': Uint8List}` or null.
  /// [iconNames] are extra icon-theme names to try before the package name.
  static Future<Map<String, dynamic>?> getLinuxAppIcon(
    String packageName, {
    List<String> iconNames = const [],
  }) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getLinuxAppIcon',
      {'package': packageName, 'iconNames': iconNames},
    );
    return result == null ? null : Map<String, dynamic>.from(result);
  }

  static Future<List<String>> getDockPackages() async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'getDockPackages',
    );
    return (result ?? const []).cast<String>();
  }

  static Future<bool> saveDockPackages(List<String> packages) async {
    return await _channel.invokeMethod<bool>('saveDockPackages', {
          'packages': packages,
        }) ??
        false;
  }

  static Future<List<Map<String, dynamic>>> listDesktopSnapshots() async {
    final result = await _channel.invokeMethod<List<dynamic>>(
      'listDesktopSnapshots',
    );
    return (result ?? const [])
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList();
  }

  static Future<Map<String, dynamic>> createDesktopSnapshot() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'createDesktopSnapshot',
    );
    return Map<String, dynamic>.from(result ?? const {});
  }

  static Future<bool> restoreDesktopSnapshot(String name) async {
    return await _channel.invokeMethod<bool>('restoreDesktopSnapshot', {
          'name': name,
        }) ??
        false;
  }

  static Future<bool> deleteDesktopSnapshot(String name) async {
    return await _channel.invokeMethod<bool>('deleteDesktopSnapshot', {
          'name': name,
        }) ??
        false;
  }

  static Future<void> openAndroidControl(String action) async {
    await _channel.invokeMethod('openAndroidControl', {'action': action});
  }

  // ── Linux Session ──

  static Future<DesktopDisplayState> getDesktopDisplays() async =>
      DesktopDisplayState.fromMap(
        desktopDisplayMap(await _channel.invokeMethod<Object?>('getDesktopDisplays')),
      );

  static Future<DesktopDisplayState> setAutoOpenOnMonitor(bool enabled) async =>
      DesktopDisplayState.fromMap(
        desktopDisplayMap(await _channel.invokeMethod<Object?>(
          'setAutoOpenOnMonitor', {'enabled': enabled},
        )),
      );

  static Future<DesktopDisplaySettings> getDesktopDisplaySettings(String displayKey) async =>
      DesktopDisplaySettings.fromMap(
        desktopDisplayMap(await _channel.invokeMethod<Object?>(
          'getDesktopDisplaySettings', {'displayKey': displayKey},
        )),
      );

  static Future<DesktopDisplayState> saveDesktopDisplaySettings(DesktopDisplaySettings settings) async =>
      DesktopDisplayState.fromMap(
        desktopDisplayMap(await _channel.invokeMethod<Object?>(
          'saveDesktopDisplaySettings', settings.toMap(),
        )),
      );

  static Future<DesktopDisplayState> openDesktopOnDisplay({
    required int displayId,
    required String displayKey,
  }) async => DesktopDisplayState.fromMap(
    desktopDisplayMap(await _channel.invokeMethod<Object?>(
      'openDesktopOnDisplay', {'displayId': displayId, 'displayKey': displayKey},
    )),
  );

  static Future<DesktopDisplayState> moveDesktopToPhone() async =>
      DesktopDisplayState.fromMap(
        desktopDisplayMap(await _channel.invokeMethod<Object?>('moveDesktopToPhone')),
      );

  static Future<bool> startLinux({
    String de = 'xfce4',
    String mode = 'x11',
  }) async {
    return await _channel.invokeMethod<bool>('startLinux', {
          'de': de,
          'mode': mode,
        }) ??
        false;
  }

  static Future<void> stopLinux() async {
    await _channel.invokeMethod('stopLinux');
  }

  static Future<void> launchDesktopActivity() async {
    await _channel.invokeMethod('launchDesktopActivity');
  }

  static Future<String> executeCommand(String command) async {
    final result = await _channel.invokeMethod('executeCommand', {
      'command': command,
    });
    return result as String? ?? '';
  }

  static Future<void> interruptCommand() async {
    await _channel.invokeMethod('interruptCommand');
  }

  // ── Battery Optimization ──

  static Future<void> requestBatteryOptimization() async {
    await _channel.invokeMethod('requestBatteryOptimization');
  }

  static Future<bool> isBatteryOptimized() async {
    final result = await _channel.invokeMethod('isBatteryOptimized');
    return result as bool? ?? true;
  }

  static Future<void> requestDefaultLauncher() async {
    await _channel.invokeMethod('requestDefaultLauncher');
  }

  static Future<bool> unsetDefaultLauncher() async {
    return await _channel.invokeMethod<bool>('unsetDefaultLauncher') ?? false;
  }

  static Future<bool> isDefaultLauncher() async {
    return await _channel.invokeMethod<bool>('isDefaultLauncher') ?? false;
  }
}

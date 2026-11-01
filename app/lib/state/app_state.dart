import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/services/update_service.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/models/desktop_display.dart';

/// Central state management for the entire DroidDesk app.
class AppState extends ChangeNotifier {
  bool _disposed = false;
  DesktopDisplayState _desktopDisplays = DesktopDisplayState();
  bool _desktopOperationBusy = false;
  String? _desktopOperationError;
  int _desktopEventRevision = 0;

  DesktopDisplayState get desktopDisplays => _desktopDisplays;
  bool get desktopDisplayBusy => _desktopOperationBusy || _desktopDisplays.isMoving;
  String? get desktopOperationError => _desktopOperationError;
  String? get desktopDisplayError =>
      _desktopOperationError ?? _desktopDisplays.error?.toString();

  void _attachDisplayCallbacks() {
    if (_disposed) return;
    DroidDeskPlatform.onDesktopDisplayStateChanged = _receiveDesktopDisplays;
    DroidDeskPlatform.onDesktopDisplayError = _receiveDesktopDisplayError;
  }

  void _receiveDesktopDisplays(DesktopDisplayState state) {
    if (_disposed) return;
    _desktopEventRevision++;
    _desktopDisplays = state;
    notifyListeners();
    if (state.phase == DesktopDisplayPhase.stopped ||
        state.error?.code == 'SESSION_STOPPED') {
      unawaited(refreshStatus());
    }
  }

  void _receiveDesktopDisplayError(FormatException error) {
    if (_disposed) return;
    _recordDisplayError(error);
    unawaited(refreshDesktopDisplays());
  }

  void _recordDisplayError(Object error) {
    if (_disposed) return;
    _desktopOperationError = error is PlatformException
        ? '${error.code}: ${error.message ?? 'Desktop display operation failed'}'
        : error.toString();
    debugPrint('Desktop display operation failed: $error');
    notifyListeners();
  }

  void clearDesktopDisplayError() {
    if (_disposed) return;
    _desktopOperationError = null;
    notifyListeners();
  }

  Future<void> refreshDesktopDisplays() async {
    if (_disposed) return;
    _attachDisplayCallbacks();
    final revision = _desktopEventRevision;
    try {
      final state = await DroidDeskPlatform.getDesktopDisplays();
      if (_disposed || revision != _desktopEventRevision) return;
      _desktopDisplays = state;
      notifyListeners();
    } on PlatformException catch (error) {
      _recordDisplayError(error);
    } on FormatException catch (error) {
      _recordDisplayError(error);
    }
  }

  Future<DesktopDisplaySettings> getDesktopDisplaySettings(String key) =>
      DroidDeskPlatform.getDesktopDisplaySettings(key);

  Future<void> openDesktopOnDisplay(DesktopDisplayInfo display) =>
      _runDesktopOperation(() => DroidDeskPlatform.openDesktopOnDisplay(
        displayId: display.id, displayKey: display.key,
      ));

  Future<void> moveDesktopToPhone() =>
      _runDesktopOperation(DroidDeskPlatform.moveDesktopToPhone);

  Future<void> saveDesktopDisplaySettings(DesktopDisplaySettings settings) =>
      _runDesktopOperation(() => DroidDeskPlatform.saveDesktopDisplaySettings(settings));

  Future<void> _runDesktopOperation(Future<DesktopDisplayState> Function() operation) async {
    if (_disposed) return;
    if (desktopDisplayBusy) {
      _recordDisplayError(StateError('Another desktop display action is in progress'));
      return;
    }
    _desktopOperationBusy = true;
    _desktopOperationError = null;
    notifyListeners();
    final revision = _desktopEventRevision;
    try {
      final state = await operation();
      if (!_disposed && revision == _desktopEventRevision) _desktopDisplays = state;
    } on PlatformException catch (error) {
      _recordDisplayError(error);
    } on FormatException catch (error) {
      _recordDisplayError(error);
    } finally {
      if (!_disposed) {
        _desktopOperationBusy = false;
        notifyListeners();
        await refreshDesktopDisplays();
      }
    }
  }

  // ── Theme State ──
  ThemeMode _themeMode = ThemeMode.dark;

  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  bool _hasRoot = false;
  String _installedDistro = '';
  String _installedDE = '';
  String _selectedDistro = 'ubuntu';
  static const String _selectedDE = 'xfce4';
  int _setupStep = 0; // 0=welcome, 1=distro, 2=de, 3=install, 4=done

  // ── Download/Install Progress ──
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  double _extractProgress = 0.0;
  String _extractStatus = '';
  bool _isDownloading = false;
  bool _isExtracting = false;
  bool _isInstallingDE = false;
  String? _statusMessage;
  String _setupLog = '';
  Map<String, bool> _optionalApps = {};
  String? _installingOptionalApp;
  double _optionalInstallProgress = 0.0;
  String _optionalInstallStatus = '';
  String _optionalInstallLog = '';
  bool _isProotTerminal = false;

  // Terminal history
  final List<String> _terminalOutput = [
    'DroidDesk Linux Terminal\nType commands below.\n',
  ];
  List<String> get terminalOutput => _terminalOutput;

  /// A command is still running in the terminal; new lines are its stdin
  /// (answers such as apt's "[Y/n]"), not new commands.
  int _runningCommands = 0;
  bool get isCommandRunning => _runningCommands > 0;

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Auto Start ──
  bool _autoStartDesktop = false;
  bool get autoStartDesktop => _autoStartDesktop;

  // ── Getters ──
  ThemeMode get themeMode => _themeMode;
  bool get isDarkMode => DroidTheme.isDark;
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  bool get hasRoot => _hasRoot;
  String get installedDistro => _installedDistro;
  String get installedDE => _installedDE;
  String get selectedDistro => _selectedDistro;
  String get selectedDE => _selectedDE;
  int get setupStep => _setupStep;
  double get downloadProgress => _downloadProgress;
  String get downloadStatus => _downloadStatus;
  double get extractProgress => _extractProgress;
  String get extractStatus => _extractStatus;
  String? get statusMessage => _statusMessage;
  String get setupLog => _setupLog;
  bool get isDownloading => _isDownloading;
  bool get isExtracting => _isExtracting;
  bool get isInstallingDE => _isInstallingDE;
  Map<String, dynamic> get deviceInfo => _deviceInfo;
  String? get errorMessage => _errorMessage;
  Map<String, bool> get optionalApps => _optionalApps;
  String? get installingOptionalApp => _installingOptionalApp;
  double get optionalInstallProgress => _optionalInstallProgress;
  String get optionalInstallStatus => _optionalInstallStatus;
  String get optionalInstallLog => _optionalInstallLog;
  bool get isProotTerminal => _isProotTerminal;

  bool get isSetupComplete => _isBootstrapped && _installedDE.isNotEmpty;
  bool get isDEInstalled => _installedDE.isNotEmpty;

  String get gpuType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return 'Adreno (Snapdragon)';
    if (vendor.contains('mali')) return 'Mali (MediaTek/Exynos)';
    if (vendor.contains('powervr')) return 'PowerVR';
    return 'Unknown GPU';
  }

  // ── Initialization ──

  /// Re-reads the runtime status whenever the app returns to the foreground, since the
  /// desktop can be stopped from outside Flutter (e.g. the notification's Stop action).
  AppLifecycleListener? _lifecycleListener;

  Future<void> initialize() async {
    _attachDisplayCallbacks();
    DroidDeskPlatform.onPackageOperationProgress = _onPackageProgress;
    DroidDeskPlatform.onPackageOperationLog = _onPackageLog;
    _lifecycleListener ??= AppLifecycleListener(
      onResume: () {
        refreshStatus();
        refreshDesktopDisplays();
        checkForUpdate();
      },
    );
    await _loadThemeMode();

    // Set up progress callbacks
    DroidDeskPlatform.onDownloadProgress = (progress, status) {
      _downloadProgress = progress;
      _downloadStatus = status;
      if (progress < 0) {
        _isDownloading = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isDownloading = false;
      }
      notifyListeners();
    };

    DroidDeskPlatform.onExtractProgress = (progress, status) {
      _extractProgress = progress;
      _extractStatus = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onInstallProgress = (progress, status) {
      _extractProgress = progress; // reusing extract progress state for UI
      _extractStatus = status;
      _statusMessage = status;
      _isInstallingDE = progress >= 0 && progress < 1.0;
      if (progress < 0) {
        _isExtracting = false;
        _isInstallingDE = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        _isInstallingDE = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onTerminalOutput = (text) {
      if (_terminalOutput.isEmpty) _terminalOutput.add('');

      final cleanedText = text.replaceAll(RegExp(r'.*\r(?!\n)'), '');
      if (_setupStep == 3) {
        _setupLog += cleanedText;
        if (_setupLog.length > 20000) {
          _setupLog = _setupLog.substring(_setupLog.length - 20000);
        }
      }
      if (_installingOptionalApp != null) {
        _optionalInstallLog += cleanedText;
        if (_optionalInstallLog.length > 20000) {
          _optionalInstallLog = _optionalInstallLog.substring(
            _optionalInstallLog.length - 20000,
          );
        }
      }
      final lines = cleanedText.split('\n');

      for (int i = 0; i < lines.length; i++) {
        if (i == 0) {
          _terminalOutput[_terminalOutput.length - 1] += lines[i];
        } else {
          _terminalOutput.add(lines[i]);
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onOptionalInstallProgress = (progress, status) {
      _optionalInstallProgress = progress.clamp(0.0, 1.0);
      _optionalInstallStatus = status;
      notifyListeners();
    };

    await refreshStatus();
    await loadDeviceInfo();
    await refreshDesktopDisplays();
    DroidDeskPlatform.onUpdateProgress = _onUpdateProgress;
    checkForUpdate();

    final prefs = await SharedPreferences.getInstance();
    _autoStartDesktop = prefs.getBool('autoStartDesktop') ?? false;

    // Android admits auto-start on a fresh app launch; rebuilding Dart must not restart Stop.
  }

  @override
  void dispose() {
    _disposed = true;
    if (DroidDeskPlatform.onDesktopDisplayStateChanged == _receiveDesktopDisplays) {
      DroidDeskPlatform.onDesktopDisplayStateChanged = null;
    }
    if (DroidDeskPlatform.onDesktopDisplayError == _receiveDesktopDisplayError) {
      DroidDeskPlatform.onDesktopDisplayError = null;
    }
    _lifecycleListener?.dispose();
    super.dispose();
  }

  // ── Theme Control ──

  Future<void> _loadThemeMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedMode = prefs.getString('theme_mode');
      if (savedMode == 'light') {
        _themeMode = ThemeMode.light;
      } else if (savedMode == 'system') {
        _themeMode = ThemeMode.system;
      } else {
        _themeMode = ThemeMode.dark;
      }
      DroidTheme.currentThemeMode = _themeMode;
      notifyListeners();
      await DroidDeskPlatform.updateStatusBarTheme(!isDarkMode);
    } catch (_) {
      // Default to dark theme if preferences fail
    }
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    _themeMode = mode;
    DroidTheme.currentThemeMode = mode;
    notifyListeners();
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('theme_mode', mode.name);
      await DroidDeskPlatform.updateStatusBarTheme(!isDarkMode);
    } catch (_) {}
  }

  Future<void> toggleThemeMode() async {
    if (_themeMode == ThemeMode.dark) {
      await setThemeMode(ThemeMode.light);
    } else {
      await setThemeMode(ThemeMode.dark);
    }
  }

  Future<void> refreshStatus() async {
    try {
      final status = await DroidDeskPlatform.getRuntimeStatus();
      if (_disposed) return;
      _isBootstrapped = status['isBootstrapped'] == true;
      _isRunning = status['isRunning'] == true;
      _hasRoot = status['hasRoot'] == true;
      _installedDistro = status['distro']?.toString() ?? '';
      _installedDE = status['installedDE']?.toString() ?? '';

      if (_installedDE.isNotEmpty) {
        await refreshOptionalApps();
      }
      if (_disposed) return;
      notifyListeners();
    } catch (e) {
      if (_disposed) return;
      _errorMessage = 'Failed to get runtime status: $e';
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      notifyListeners();
    } catch (e) {
      // Non-fatal — continue without device info
    }
  }

  // ── Setup Flow ──

  void setSelectedDistro(String distro) {
    _selectedDistro = distro;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> setAutoStartDesktop(bool value) async {
    _autoStartDesktop = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('autoStartDesktop', value);
  }

  Future<bool> detectRootForSetup() async {
    _hasRoot = await DroidDeskPlatform.checkRoot();
    notifyListeners();
    return _hasRoot;
  }

  /// Main setup entry point. [useRoot] records confirmation from the
  /// rooted-device dialog.
  Future<void> runSetup({bool? useRoot}) async {
    try {
      _errorMessage = null;
      _setupLog = '';
      _setupStep = 3;
      notifyListeners();

      // Detect root and choose path
      final rootAvailable = await DroidDeskPlatform.checkRoot();
      _hasRoot = rootAvailable && (useRoot ?? true);
      notifyListeners();

      if (_hasRoot) {
        await _runChrootSetup();
      } else {
        await _runNativeSetup();
      }

      _setupStep = 4;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Setup failed: $e';
      _isDownloading = false;
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  Future<void> _runNativeSetup() async {
    _isExtracting = true;
    _extractProgress = 0.0;
    _extractStatus = 'Extracting native Termux bootstrap...';
    _statusMessage = _extractStatus;
    notifyListeners();
    await DroidDeskPlatform.setupBootstrap();

    _extractProgress = 0.08;
    _extractStatus = 'Bootstrap environment ready';
    _statusMessage = _extractStatus;
    _isInstallingDE = true;
    notifyListeners();
    final installed = await DroidDeskPlatform.installDesktopNative(
      de: _selectedDE,
    );
    if (!installed) {
      throw StateError('Native Termux package installation failed');
    }
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> _runChrootSetup() async {
    _statusMessage = 'Downloading Ubuntu rootfs...';
    _isDownloading = true;
    _downloadProgress = 0.0;
    notifyListeners();
    if (!await DroidDeskPlatform.downloadRootfs(_selectedDistro)) {
      throw StateError(
        'Ubuntu download failed. Check your connection and retry.',
      );
    }

    _isDownloading = false;
    _isExtracting = true;
    _extractProgress = 0.0;
    _statusMessage = 'Extracting rootfs...';
    notifyListeners();
    if (!await DroidDeskPlatform.extractRootfs()) {
      throw StateError('Ubuntu filesystem extraction failed');
    }

    _statusMessage =
        'Installing desktop environment (this may take a while)...';
    _isInstallingDE = true;
    notifyListeners();
    if (!await DroidDeskPlatform.installDesktopEnvironment(_selectedDE)) {
      throw StateError('Desktop Essentials package installation failed');
    }
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> runExtraction() async {
    // Handled inside runSetup for chroot mode.
    // Kept for API compatibility.
    _extractProgress = 1.0;
    _extractStatus = 'Extraction handled by setup flow';
    notifyListeners();
  }

  Future<void> installDesktopEnvironment() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _statusMessage = 'Installing Desktop Environment...';
      _errorMessage = null;
      notifyListeners();

      if (_hasRoot) {
        if (!await DroidDeskPlatform.installDesktopEnvironment(_selectedDE)) {
          throw StateError('Desktop Essentials package installation failed');
        }
      } else {
        final installed = await DroidDeskPlatform.installDesktopNative(
          de: _selectedDE,
        );
        if (!installed) {
          throw StateError('Native Termux package installation failed');
        }
      }

      _isExtracting = false;
      _isInstallingDE = false;
      await refreshStatus();
    } catch (e) {
      _errorMessage = 'Installation failed: $e';
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  // ── Optional applications ──

  Future<void> refreshOptionalApps() async {
    try {
      final apps = await DroidDeskPlatform.getOptionalApps();
      if (_disposed) return;
      _optionalApps = apps;
      notifyListeners();
    } catch (_) {
      // The desktop remains usable even if package status cannot be queried.
    }
  }

  Future<bool> installOptionalApp(String appId) async {
    if (_installingOptionalApp != null) return false;
    _installingOptionalApp = appId;
    _optionalInstallProgress = 0.0;
    _optionalInstallStatus = 'Preparing installation...';
    _optionalInstallLog = '';
    notifyListeners();

    try {
      final installed = await DroidDeskPlatform.installOptionalApp(appId);
      await refreshOptionalApps();
      return installed;
    } finally {
      _installingOptionalApp = null;
      notifyListeners();
    }
  }

  // ── In-app updates ──

  String _appVersion = '';
  AppRelease? _availableUpdate;
  bool _checkingUpdate = false;
  bool _downloadingUpdate = false;
  int _updateDownloaded = 0;
  int _updateTotal = 0;
  String? _updateError;
  String? _updateErrorCode;
  String? _downloadedUpdatePath;
  bool _updatePromptShown = false;

  String get appVersion => _appVersion;
  AppRelease? get availableUpdate => _availableUpdate;
  bool get isCheckingUpdate => _checkingUpdate;
  bool get isDownloadingUpdate => _downloadingUpdate;
  int get updateDownloaded => _updateDownloaded;
  int get updateTotal => _updateTotal;
  double get updateProgress =>
      _updateTotal > 0 ? (_updateDownloaded / _updateTotal).clamp(0.0, 1.0) : 0;
  String? get updateError => _updateError;
  String? get updateErrorCode => _updateErrorCode;
  String? get downloadedUpdatePath => _downloadedUpdatePath;

  /// True once per app session when an automatic check found an update to offer.
  bool takeUpdatePrompt() {
    if (_availableUpdate == null || _updatePromptShown) return false;
    _updatePromptShown = true;
    return true;
  }

  void _onUpdateProgress(int downloaded, int total) {
    _updateDownloaded = downloaded;
    _updateTotal = total;
    notifyListeners();
  }

  Future<String> _installedVersion() async {
    if (_appVersion.isEmpty) {
      try {
        final info = await DroidDeskPlatform.getAppVersion();
        _appVersion = info['versionName']?.toString() ?? '';
      } catch (_) {}
    }
    return _appVersion;
  }

  /// Looks for a newer GitHub release. Automatic checks are throttled and silent;
  /// [manual] checks always run and surface errors in [updateError].
  Future<void> checkForUpdate({bool manual = false}) async {
    if (_checkingUpdate || _downloadingUpdate) return;
    _checkingUpdate = true;
    if (manual) {
      _updateError = null;
      _updateErrorCode = null;
    }
    notifyListeners();
    try {
      final installed = await _installedVersion();
      if (installed.isEmpty) return;
      final release = await UpdateService.check(installedVersion: installed, manual: manual);
      if (release != null || manual) {
        if (_availableUpdate?.tag != release?.tag) _downloadedUpdatePath = null;
        _availableUpdate = release;
      }
      if (release == null && manual) DroidDeskPlatform.cleanupUpdates();
    } catch (error) {
      if (manual) {
        _updateError = 'Could not reach GitHub. Check your connection.';
        _updateErrorCode = 'network';
      }
    } finally {
      _checkingUpdate = false;
      notifyListeners();
    }
  }

  Future<void> skipUpdate() async {
    final release = _availableUpdate;
    if (release == null) return;
    await UpdateService.skip(release.tag);
    _availableUpdate = null;
    notifyListeners();
  }

  /// Downloads (if needed) and opens the installer. Returns false on error or cancel.
  Future<bool> downloadAndInstallUpdate() async {
    final release = _availableUpdate;
    if (release == null || _downloadingUpdate) return false;
    _updateError = null;
    _updateErrorCode = null;
    var path = _downloadedUpdatePath;
    if (path == null) {
      _downloadingUpdate = true;
      _updateDownloaded = 0;
      _updateTotal = release.apkSize;
      notifyListeners();
      final result = await DroidDeskPlatform.downloadUpdate(
        url: release.apkUrl,
        size: release.apkSize,
        tag: release.tag,
      );
      _downloadingUpdate = false;
      if (result['ok'] != true) {
        _updateErrorCode = result['code']?.toString();
        _updateError = switch (_updateErrorCode) {
          'cancelled' => null,
          'signature_mismatch' =>
            'This release is signed with a different key. Install it once from the release page.',
          'size_mismatch' || 'network' => 'The download did not finish. Check your connection and try again.',
          _ => result['message']?.toString() ?? 'The update could not be downloaded.',
        };
        notifyListeners();
        return false;
      }
      path = result['path'].toString();
      _downloadedUpdatePath = path;
    }
    notifyListeners();
    final opened = await DroidDeskPlatform.installUpdate(path);
    if (!opened) {
      _updateError = 'Could not open the installer.';
      _updateErrorCode = 'installer';
      notifyListeners();
    }
    return opened;
  }

  Future<void> cancelUpdateDownload() async {
    if (!_downloadingUpdate) return;
    await DroidDeskPlatform.cancelUpdateDownload();
  }

  // ── Package operations ──
  //
  // Installs and removals from the App Store live here rather than in the screen, so they keep
  // running (and stay visible on Home) when the user navigates away.

  String? _activePackage;
  String _activePackageName = '';
  List<String> _activePackageIconNames = const [];
  bool _packageRemoving = false;
  bool _packageCancelling = false;
  double _packageProgress = 0;
  String _packageStatus = '';
  String _packageLog = '';
  PackageOperationResult? _lastPackageResult;
  int _packageResultId = 0;

  String? get activePackage => _activePackage;
  String get activePackageName => _activePackageName;
  List<String> get activePackageIconNames => _activePackageIconNames;
  bool get packageRemoving => _packageRemoving;
  bool get packageCancelling => _packageCancelling;
  double get packageProgress => _packageProgress;
  String get packageStatus => _packageStatus;
  String get packageLog => _packageLog;

  /// Result of the most recent finished operation; [packageResultId] changes with each one.
  PackageOperationResult? get lastPackageResult => _lastPackageResult;
  int get packageResultId => _packageResultId;

  void _onPackageProgress(double progress, String status) {
    if (_activePackage == null) return;
    _packageProgress = progress.clamp(0.0, 1.0);
    _packageStatus = status;
    notifyListeners();
  }

  void _onPackageLog(String text) {
    if (_activePackage == null) return;
    _packageLog += text;
    if (_packageLog.length > 12000) {
      _packageLog = _packageLog.substring(_packageLog.length - 12000);
    }
    notifyListeners();
  }

  void _startPackageOperation(
    String package,
    String displayName,
    List<String> iconNames, {
    required bool removing,
  }) {
    _activePackage = package;
    _activePackageName = displayName;
    _activePackageIconNames = iconNames;
    _packageRemoving = removing;
    _packageCancelling = false;
    _packageProgress = 0;
    _packageStatus = removing ? 'Preparing removal...' : 'Preparing $displayName...';
    _packageLog = '';
    notifyListeners();
  }

  void _finishPackageOperation(bool ok) {
    final result = PackageOperationResult(
      package: _activePackage ?? '',
      displayName: _activePackageName,
      iconNames: _activePackageIconNames,
      removed: _packageRemoving,
      cancelled: _packageCancelling,
      ok: ok,
    );
    _activePackage = null;
    _packageCancelling = false;
    _lastPackageResult = result;
    _packageResultId++;
    notifyListeners();
  }

  /// Installs a native package; returns false if another operation is running or it failed.
  Future<bool> installPackage(
    String package, {
    String? displayName,
    List<String> iconNames = const [],
  }) async {
    if (_activePackage != null) return false;
    _startPackageOperation(package, displayName ?? package, iconNames, removing: false);
    var ok = false;
    try {
      ok = await DroidDeskPlatform.installNativePackage(package);
    } catch (_) {}
    _finishPackageOperation(ok);
    return ok;
  }

  Future<bool> removePackage(
    String package, {
    String? displayName,
    List<String> iconNames = const [],
  }) async {
    if (_activePackage != null) return false;
    _startPackageOperation(package, displayName ?? package, iconNames, removing: true);
    var ok = false;
    try {
      ok = await DroidDeskPlatform.removeNativePackage(package);
    } catch (_) {}
    _finishPackageOperation(ok);
    return ok;
  }

  /// Package id used for the "update everything" operation.
  static const updatesOperation = '__updates__';

  bool get isUpdatingAll => _activePackage == updatesOperation;

  /// Upgrades all non-core packages through the same progress UI as installs.
  Future<bool> updateAllPackages() async {
    if (_activePackage != null) return false;
    _startPackageOperation(updatesOperation, 'packages', const [], removing: false);
    _packageStatus = 'Checking for updates...';
    var ok = false;
    try {
      ok = await DroidDeskPlatform.upgradeAllPackages();
    } catch (_) {}
    _finishPackageOperation(ok);
    return ok;
  }

  Future<void> cancelPackageOperation() async {
    if (_activePackage == null || _packageCancelling || _packageRemoving) return;
    _packageCancelling = true;
    _packageStatus = 'Cancelling installation...';
    notifyListeners();
    await DroidDeskPlatform.cancelNativePackageOperation();
  }

  /// Hides the "finished" card on Home.
  void dismissPackageResult() {
    if (_lastPackageResult == null) return;
    _lastPackageResult = null;
    notifyListeners();
  }

  // ── Session Control ──

  Future<void> startLinux({
    String mode = 'x11',
  }) async {
    try {
      _errorMessage = null;
      final started = await DroidDeskPlatform.startLinux(
        de: _selectedDE,
        mode: mode,
      );
      if (!started) {
        throw StateError('Linux runtime is not ready');
      }
      _isRunning = true;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to start: $e';
      notifyListeners();
    }
  }

  Future<void> launchDesktopActivity() async {
    try {
      await DroidDeskPlatform.launchDesktopActivity();
    } catch (e) {
      _errorMessage = 'Failed to launch desktop activity: $e';
      notifyListeners();
    }
  }

  Future<void> stopLinux() async {
    try {
      await DroidDeskPlatform.stopLinux();
      _isRunning = false;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to stop: $e';
      notifyListeners();
    }
  }

  Future<String> executeCommand(String command) async {
    final isInput = isCommandRunning;
    if (!isInput) _runningCommands++;
    try {
      _terminalOutput.add(isInput ? '$command\n' : '\$ $command\n');
      notifyListeners();
      return await DroidDeskPlatform.executeCommand(command);
    } catch (e) {
      return "Error executing command: $e";
    } finally {
      if (!isInput) {
        _runningCommands--;
        notifyListeners();
      }
    }
  }

  void useNativeTerminal() {
    _isProotTerminal = false;
    notifyListeners();
  }

  Future<void> startDebianShell() async {
    _isProotTerminal = true;
    clearTerminal();
    await executeCommand('start-debian');
  }

  void appendTerminalOutput(String output) {
    if (_terminalOutput.isEmpty) _terminalOutput.add('');
    _terminalOutput[_terminalOutput.length - 1] += output;
    notifyListeners();
  }

  void clearTerminal() {
    _terminalOutput.clear();
    _terminalOutput.add('DroidDesk Linux Terminal\nType commands below.\n');
    notifyListeners();
  }

  Future<void> interruptCommand() async {
    try {
      await DroidDeskPlatform.interruptCommand();
    } catch (e) {
      debugPrint("Error interrupting command: $e");
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}

/// Outcome of a finished App Store install or removal.
class PackageOperationResult {
  final String package;
  final String displayName;
  final List<String> iconNames;
  final bool removed;
  final bool cancelled;
  final bool ok;

  const PackageOperationResult({
    required this.package,
    required this.displayName,
    required this.iconNames,
    required this.removed,
    required this.cancelled,
    required this.ok,
  });

  bool get isUpdateAll => package == AppState.updatesOperation;

  String get message => isUpdateAll
      ? (cancelled
          ? 'Update cancelled'
          : ok
              ? 'Packages updated'
              : 'Some updates failed')
      : cancelled
      ? '$displayName installation cancelled'
      : removed
          ? (ok ? '$displayName removed' : 'Could not remove $displayName')
          : (ok ? '$displayName installed' : '$displayName installation failed');
}

import 'package:flutter/material.dart';
import 'package:droiddesk/services/platform_bridge.dart';

/// Central state management for the entire DroidDesk app.
class AppState extends ChangeNotifier {
  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  bool _hasRoot = false;
  String _installedDistro = '';
  String _installedDE = '';
  String _selectedDistro = 'ubuntu';
  String _selectedDE = 'xfce4';
  bool _deExplicitlyChosen = false;
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

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Getters ──
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  bool get hasRoot => _hasRoot;
  String get installedDistro => _installedDistro;
  String get installedDE => _installedDE;
  String get selectedDistro => _selectedDistro;
  // Once a desktop is installed, it is the source of truth everywhere (labels,
  // status, launch). Before install, fall back to the picker selection.
  String get selectedDE => _installedDE.isNotEmpty ? _installedDE : _selectedDE;
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

  int get totalRamMB {
    final ram = _deviceInfo['totalRamMB'];
    if (ram is int) return ram;
    return int.tryParse('$ram') ?? 0;
  }

  /// Devices with ~3 GB or less RAM (e.g. Samsung Galaxy A10s, 2 GB) run the
  /// lightweight Openbox profile at a lower resolution by default. 0 means the
  /// device info has not loaded yet, which we treat as a normal device.
  bool get isLowRamDevice {
    final ram = totalRamMB;
    return ram > 0 && ram <= 3072;
  }

  // ── Initialization ──

  Future<void> initialize() async {
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
  }

  Future<void> refreshStatus() async {
    try {
      final status = await DroidDeskPlatform.getRuntimeStatus();
      _isBootstrapped = status['isBootstrapped'] == true;
      _isRunning = status['isRunning'] == true;
      _hasRoot = status['hasRoot'] == true;
      _installedDistro = status['distro']?.toString() ?? '';
      _installedDE = status['installedDE']?.toString() ?? '';

      if (_installedDE.isNotEmpty) {
        await refreshOptionalApps();
      }

      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to get runtime status: $e';
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      // On a fresh low-RAM device, default to the lightweight Openbox desktop.
      // Never override an explicit user choice or an already-installed desktop.
      if (!_deExplicitlyChosen &&
          _installedDE.isEmpty &&
          isLowRamDevice) {
        _selectedDE = 'openbox';
      }
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

  void setSelectedDE(String de) {
    _selectedDE = de;
    _deExplicitlyChosen = true;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
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
      _optionalApps = await DroidDeskPlatform.getOptionalApps();
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

  // ── Session Control ──

  Future<void> startLinux({
    String mode = 'x11',
    int width = 1920,
    int height = 1080,
  }) async {
    try {
      _errorMessage = null;
      // Launch the desktop that is actually installed. The picker default
      // (_selectedDE) may differ from what was installed — e.g. on a low-RAM
      // device that installed Openbox — and starting the wrong one tries to
      // (re)install it and fails.
      final launchDE = _installedDE.isNotEmpty ? _installedDE : _selectedDE;
      final started = await DroidDeskPlatform.startLinux(
        de: launchDE,
        mode: mode,
        width: width,
        height: height,
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
    try {
      _terminalOutput.add('\$ $command\n');
      notifyListeners();
      return await DroidDeskPlatform.executeCommand(command);
    } catch (e) {
      return "Error executing command: $e";
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

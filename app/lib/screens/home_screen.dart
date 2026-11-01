import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/widgets/package_operation_card.dart';
import 'package:droiddesk/widgets/update_sheet.dart';
import 'package:droiddesk/screens/storage_screen.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/screens/setup/de_install_screen.dart';
import 'package:droiddesk/screens/apps/app_catalog_screen.dart';
import 'package:droiddesk/screens/desktop_tools_screen.dart';
import 'package:droiddesk/screens/display_settings_screen.dart';

/// Home dashboard — shown after setup is complete.
/// Central hub for launching the desktop, terminal, and managing the environment.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    final isDark = state.isDarkMode;

    promptForUpdateIfAvailable(context, state);

    final overlayStyle = SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: isDark ? Brightness.light : Brightness.dark,
      statusBarBrightness: isDark ? Brightness.dark : Brightness.light,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarIconBrightness: isDark ? Brightness.light : Brightness.dark,
    );

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: overlayStyle,
      child: Scaffold(
        body: Container(
        decoration: BoxDecoration(
          gradient: DroidTheme.backgroundGradient,
        ),
        child: SafeArea(
          child: CustomScrollView(
            slivers: [
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 18, 18, 0),
                  child: Row(
                    children: [
                      Container(
                        width: 44,
                        height: 44,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(13),
                        ),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(13),
                          child: Image.asset(
                            'assets/icons/logo.png',
                            fit: BoxFit.cover,
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('DroidDesk', style: DroidTheme.headingMd),
                          Text(
                            state.isRunning
                                ? 'Desktop Environment is running'
                                : 'Desktop Environment',
                            style: DroidTheme.bodySm.copyWith(
                              color: state.isRunning
                                  ? DroidTheme.accent
                                  : DroidTheme.textMuted,
                            ),
                          ),
                        ],
                      ),
                      const Spacer(),
                      Container(
                        decoration: BoxDecoration(
                          color: DroidTheme.surfaceLight,
                          shape: BoxShape.circle,
                        ),
                        child: IconButton(
                          onPressed: () => state.toggleThemeMode(),
                          tooltip: state.isDarkMode
                              ? 'Switch to Light Theme'
                              : 'Switch to Dark Theme',
                          icon: Icon(
                            state.isDarkMode
                                ? Icons.light_mode_outlined
                                : Icons.dark_mode_outlined,
                            color: DroidTheme.textSecondary,
                            size: 21,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Container(
                        decoration: BoxDecoration(
                          color: DroidTheme.surfaceLight,
                          shape: BoxShape.circle,
                        ),
                        child: IconButton(
                          onPressed: () => _showSettings(context),
                          icon: Icon(
                            Icons.settings_outlined,
                            color: DroidTheme.textSecondary,
                            size: 21,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),

              // ── Status Card ──
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
                  child: _buildStatusCard(state)
                      .animate()
                      .fadeIn(duration: 500.ms)
                      .slideY(begin: 0.05, duration: 500.ms),
                ),
              ),

              // ── Running / finished App Store install, pinned ──
              if (state.desktopDisplayError != null) SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 0),
                  child: Card(child: ListTile(
                    leading: const Icon(Icons.monitor, color: DroidTheme.error),
                    title: Text(state.desktopDisplayError!),
                    trailing: TextButton(
                      onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => const DisplaySettingsScreen(),
                      )),
                      child: const Text('Displays'),
                    ),
                  )),
                ),
              ),
              SliverToBoxAdapter(
                child: PackageOperationCard(
                  showResult: true,
                  margin: const EdgeInsets.fromLTRB(24, 14, 24, 0),
                  onTap: () {
                    context.read<AppState>().dismissPackageResult();
                    Navigator.of(context).push(
                      MaterialPageRoute(builder: (_) => const AppCatalogScreen()),
                    );
                  },
                ),
              ),

              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 20, 24, 0),
                  child: Text(
                    'Workspace',
                    style: DroidTheme.headingMd,
                  ).animate().fadeIn(delay: 200.ms, duration: 400.ms),
                ),
              ),

              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 0),
                  child: Column(
                    children:
                        [
                              // Installation is only actionable when setup is missing.
                              // Do not show an "Installed" card that can reinstall the DE.
                              if (!state.isDEInstalled) ...[
                                _ActionCard(
                                  icon: Icons.download_rounded,
                                  title: 'Install Desktop Environment',
                                  subtitle: 'Prepare your desktop workspace',
                                  color: DroidTheme.secondary,
                                  onTap: () {
                                    Navigator.of(context).push(
                                      MaterialPageRoute(
                                        builder: (_) => const DEInstallScreen(),
                                      ),
                                    );
                                  },
                                ),
                                const SizedBox(height: 10),
                              ],

                              // ── Launch Desktop / Reconnect ──
                              if (state.isRunning)
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 10),
                                  child: _ActionCard(
                                    icon: Icons.fullscreen_rounded,
                                    title: 'Return to Desktop',
                                    subtitle:
                                        'Your session is waiting in the background',
                                    color: DroidTheme.primary,
                                    gradient: DroidTheme.primaryGradient,
                                    onTap: () {
                                      state.launchDesktopActivity();
                                    },
                                  ),
                                ),

                              _ActionCard(
                                icon: state.isRunning
                                    ? Icons.stop_circle_rounded
                                    : Icons.desktop_mac_rounded,
                                title: state.isRunning
                                    ? 'Stop Server'
                                    : 'Launch Desktop',
                                subtitle: state.isRunning
                                    ? 'Shutdown Linux environment'
                                    : 'Open your Desktop Environment',
                                color: state.isRunning
                                    ? DroidTheme.error
                                    : DroidTheme.primary,
                                gradient: state.isRunning
                                    ? null
                                    : DroidTheme.primaryGradient,
                                onTap: () async {
                                  if (state.isRunning) {
                                    state.stopLinux();
                                  } else {
                                    if (!state.isDEInstalled) {
                                      ScaffoldMessenger.of(
                                        context,
                                      ).showSnackBar(
                                        const SnackBar(
                                          content: Text(
                                            'Desktop Environment is not installed. Complete setup first.',
                                          ),
                                          backgroundColor: DroidTheme.error,
                                        ),
                                      );
                                      return;
                                    }
                                    await state.startLinux(mode: 'x11');
                                  }
                                },
                              ),

                              const SizedBox(height: 10),

                              // ── Terminal ──
                              _ActionCard(
                                icon: Icons.monitor_rounded,
                                title: 'Displays and scaling',
                                subtitle: 'Open on monitor and adjust independent UI scale',
                                color: DroidTheme.secondary,
                                onTap: () => Navigator.of(context).push(MaterialPageRoute(
                                  builder: (_) => const DisplaySettingsScreen(),
                                )),
                              ),
                              const SizedBox(height: 10),
                              _ActionCard(
                                icon: Icons.terminal_rounded,
                                title: 'Terminal',
                                subtitle: 'Open the Linux command line',
                                color: DroidTheme.secondary,
                                onTap: () {
                                  state.useNativeTerminal();
                                  _showTerminal(context, state);
                                },
                              ),

                              if (!state.hasRoot &&
                                  state.optionalApps['proot_debian'] ==
                                      true) ...[
                                const SizedBox(height: 10),
                                _ActionCard(
                                  icon: Icons.inventory_2_rounded,
                                  title: 'Debian shell',
                                  subtitle:
                                      'Open the optional minimal PRoot compatibility environment',
                                  color: const Color(0xFFD70A53),
                                  onTap: () =>
                                      _showDebianTerminal(context, state),
                                ),
                              ],

                              const SizedBox(height: 10),

                              _ActionCard(
                                icon: Icons.apps_rounded,
                                title: 'Add applications',
                                subtitle: 'Expand your Linux workspace',
                                color: DroidTheme.primaryLight,
                                onTap: () {
                                  Navigator.of(context).push(
                                    MaterialPageRoute(
                                      builder: (_) => const AppCatalogScreen(),
                                    ),
                                  );
                                },
                              ),

                              const SizedBox(height: 10),
                            ]
                            .animate(interval: 80.ms)
                            .fadeIn(delay: 300.ms, duration: 400.ms)
                            .slideY(begin: 0.05, duration: 400.ms),
                  ),
                ),
              ),

              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
                  child: Text(
                    'Device',
                    style: DroidTheme.headingMd,
                  ).animate().fadeIn(delay: 500.ms, duration: 400.ms),
                ),
              ),

              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
                  child: Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: DroidTheme.cardBg,
                      borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
                      border: Border.all(color: DroidTheme.surfaceBorder),
                    ),
                    child: Column(
                      children: [
                        _infoRow('Workspace', 'Desktop Environment'),
                        _divider(),
                        _infoRow(
                          'Renderer',
                          state.deviceInfo['graphicsMode']?.toString() ??
                              'Automatic',
                        ),
                        _divider(),
                        _infoRow(
                          'Device',
                          '${state.deviceInfo['brand'] ?? ''} ${state.deviceInfo['model'] ?? ''}',
                        ),
                        _divider(),
                        _infoRow(
                          'Storage available',
                          '${state.deviceInfo['availableStorageMB'] ?? '—'} MB',
                        ),
                      ],
                    ),
                  ).animate().fadeIn(delay: 600.ms, duration: 400.ms),
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

  // ── Status Card ──

  Widget _buildStatusCard(AppState state) {
    final isDark = DroidTheme.isDark;

    final activeGradient = isDark
        ? const LinearGradient(
            colors: [Color(0xFF112B1A), Color(0xFF17231B)],
          )
        : const LinearGradient(
            colors: [Color(0xFFECFDF5), Color(0xFFE6F4EA)],
          );

    final activeBorderColor = isDark
        ? DroidTheme.accent.withValues(alpha: 0.3)
        : const Color(0xFFA7F3D0);

    final activeTitleColor = isDark
        ? DroidTheme.accent
        : const Color(0xFF047857);

    final activeSubtitleColor = isDark
        ? const Color(0xFFA7F3D0)
        : const Color(0xFF065F46);

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: state.isRunning ? activeGradient : DroidTheme.cardGradient,
        borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
        border: Border.all(
          color: state.isRunning ? activeBorderColor : DroidTheme.surfaceBorder,
        ),
      ),
      child: Row(
        children: [
          // Status indicator
          Container(
            width: 12,
            height: 12,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: state.isRunning
                  ? (isDark ? DroidTheme.accent : const Color(0xFF10B981))
                  : DroidTheme.textDim,
              boxShadow: state.isRunning
                  ? [
                      BoxShadow(
                        color: DroidTheme.accent.withValues(alpha: 0.4),
                        blurRadius: 10,
                      ),
                    ]
                  : [],
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  state.isRunning ? 'Desktop active' : 'Ready when you are',
                  style: DroidTheme.headingSm.copyWith(
                    color: state.isRunning
                        ? activeTitleColor
                        : DroidTheme.textPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  state.isRunning
                      ? 'Desktop Environment · ${_distroLabel(state.installedDistro)}'
                      : 'Launch Desktop Environment to begin',
                  style: DroidTheme.bodySm.copyWith(
                    color: state.isRunning
                        ? activeSubtitleColor
                        : DroidTheme.textMuted,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // ── Helpers ──

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Text(label, style: DroidTheme.bodySm),
          const Spacer(),
          Text(
            value,
            style: DroidTheme.monoSm.copyWith(color: DroidTheme.textSecondary),
          ),
        ],
      ),
    );
  }

  Widget _divider() {
    return Divider(
      height: 1,
      color: DroidTheme.surfaceBorder.withValues(alpha: 0.5),
    );
  }

  String _distroLabel(String distro) {
    switch (distro) {
      case 'ubuntu-chroot':
        return 'Ubuntu 24.04 (chroot)';
      case 'ubuntu':
        return 'Ubuntu 24.04';
      case 'alpine':
        return 'Alpine Linux 3.20';
      case 'kali':
        return 'Kali Linux';
      case 'termux-native':
        return 'Termux Native';
      default:
        return distro;
    }
  }

  // ── Dialogs ──

  void _showSettings(BuildContext pageContext) {
    showModalBottomSheet(
      context: pageContext,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (sheetContext) => Consumer<AppState>(
        builder: (context, state, _) {
          return Container(
            decoration: BoxDecoration(
              color: DroidTheme.surface,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(DroidTheme.radiusXl),
              ),
              border: Border(
                top: BorderSide(color: DroidTheme.surfaceBorder),
              ),
            ),
            constraints: BoxConstraints(
              maxHeight: MediaQuery.of(sheetContext).size.height * 0.88,
            ),
            child: SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(
                20,
                10,
                20,
                24 + MediaQuery.of(sheetContext).padding.bottom,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Container(
                      width: 38,
                      height: 5,
                      decoration: BoxDecoration(
                        color: DroidTheme.surfaceBorder,
                        borderRadius: BorderRadius.circular(3),
                      ),
                    ),
                  ),
                  const SizedBox(height: 18),
                  Text('Settings', style: DroidTheme.headingLg),
                  const SizedBox(height: 22),

                  _SettingsSectionLabel('Appearance'),
                  Row(
                    children: [
                      Expanded(
                        child: _ThemeOption(
                          label: 'Dark',
                          icon: Icons.dark_mode_rounded,
                          preview: const [Color(0xFF000000), Color(0xFF1C1C1E)],
                          selected: state.themeMode == ThemeMode.dark,
                          onTap: () => state.setThemeMode(ThemeMode.dark),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: _ThemeOption(
                          label: 'Light',
                          icon: Icons.light_mode_rounded,
                          preview: const [Color(0xFFF2F2F7), Color(0xFFFFFFFF)],
                          selected: state.themeMode == ThemeMode.light,
                          onTap: () => state.setThemeMode(ThemeMode.light),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: _ThemeOption(
                          label: 'System',
                          icon: Icons.brightness_auto_rounded,
                          preview: const [Color(0xFF000000), Color(0xFFF2F2F7)],
                          selected: state.themeMode == ThemeMode.system,
                          onTap: () => state.setThemeMode(ThemeMode.system),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 26),

                  _SettingsSectionLabel('Desktop'),
                  _SettingsGroup(
                    children: [
                      _SettingsRow(
                        icon: Icons.bolt_rounded,
                        color: DroidTheme.primary,
                        title: 'Open automatically',
                        subtitle: 'Start the desktop on launch',
                        trailing: Switch.adaptive(
                          value: state.autoStartDesktop,
                          activeThumbColor: Colors.white,
                          activeTrackColor: DroidTheme.accent,
                          inactiveThumbColor: Colors.white,
                          inactiveTrackColor: DroidTheme.surfaceBorder,
                          trackOutlineColor:
                              const WidgetStatePropertyAll(Colors.transparent),
                          onChanged: state.setAutoStartDesktop,
                        ),
                        onTap: () =>
                            state.setAutoStartDesktop(!state.autoStartDesktop),
                      ),
                      _SettingsRow(
                        icon: Icons.auto_awesome_rounded,
                        color: DroidTheme.accent,
                        title: 'Desktop Tools',
                        subtitle: 'Dock apps and desktop backups',
                        onTap: () {
                          Navigator.pop(sheetContext);
                          Navigator.of(pageContext).push(
                            MaterialPageRoute(
                              builder: (_) => const DesktopToolsScreen(),
                            ),
                          );
                        },
                      ),
                    ],
                  ),
                  const SizedBox(height: 26),

                  _SettingsSectionLabel('System'),
                  FutureBuilder<List<bool>>(
                    future: Future.wait([
                      DroidDeskPlatform.isDefaultLauncher(),
                      DroidDeskPlatform.isBatteryOptimized(),
                    ]),
                    builder: (_, snapshot) {
                      final isLauncher = snapshot.data?[0] == true;
                      // Unknown until loaded; only show "Allowed" once Android confirms it.
                      final batteryUnrestricted = snapshot.data?[1] == false;
                      return _SettingsGroup(
                        children: [
                          _SettingsRow(
                            icon: Icons.system_update_rounded,
                            color: DroidTheme.primary,
                            title: 'App version',
                            subtitle: state.isCheckingUpdate
                                ? 'Checking for updates…'
                                : state.availableUpdate != null
                                    ? 'v${state.availableUpdate!.version} available'
                                    : state.updateErrorCode == 'network'
                                        ? 'v${state.appVersion} · Could not check'
                                        : 'v${state.appVersion} · Tap to check for updates',
                            trailing: state.availableUpdate != null
                                ? const _StatusPill(
                                    label: 'Update',
                                    color: DroidTheme.primary,
                                    icon: Icons.arrow_circle_down_rounded,
                                  )
                                : null,
                            onTap: () async {
                              if (state.availableUpdate == null) {
                                await state.checkForUpdate(manual: true);
                              }
                              if (state.availableUpdate != null && sheetContext.mounted) {
                                Navigator.pop(sheetContext);
                                if (pageContext.mounted) showUpdateSheet(pageContext);
                              }
                            },
                          ),
                          _SettingsRow(
                            icon: Icons.pie_chart_rounded,
                            color: const Color(0xFFBF5AF2),
                            title: 'Storage',
                            subtitle: 'Space used by Linux and clean-up',
                            onTap: () {
                              Navigator.pop(sheetContext);
                              Navigator.of(pageContext).push(
                                MaterialPageRoute(builder: (_) => const StorageScreen()),
                              );
                            },
                          ),
                          if (batteryUnrestricted)
                            _SettingsRow(
                              icon: Icons.battery_charging_full_rounded,
                              color: DroidTheme.accent,
                              title: 'Battery optimization',
                              subtitle: 'Unrestricted in background',
                              trailing: _StatusPill(label: 'Allowed'),
                            )
                          else
                            _SettingsRow(
                              icon: Icons.battery_charging_full_rounded,
                              color: DroidTheme.warning,
                              title: 'Battery optimization',
                              subtitle: 'Keep the session alive in background',
                              onTap: () {
                                DroidDeskPlatform.requestBatteryOptimization();
                                Navigator.pop(sheetContext);
                              },
                            ),
                          if (isLauncher)
                            _SettingsRow(
                              icon: Icons.home_rounded,
                              color: DroidTheme.error,
                              title: 'Stop using as Home app',
                              subtitle: 'Choose another launcher',
                              onTap: () {
                                Navigator.pop(sheetContext);
                                _confirmUnsetLauncher(pageContext);
                              },
                            )
                          else
                            _SettingsRow(
                              icon: Icons.home_rounded,
                              color: DroidTheme.secondary,
                              title: 'Set as Home app',
                              subtitle: 'Open Linux when the phone starts',
                              onTap: () {
                                Navigator.pop(sheetContext);
                                DroidDeskPlatform.requestDefaultLauncher();
                              },
                            ),
                        ],
                      );
                    },
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Future<void> _confirmUnsetLauncher(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Change default launcher?'),
        content: const Text(
          'DroidDesk will stop opening automatically as your Home app. Android will ask you to choose another launcher.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Change launcher'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await DroidDeskPlatform.unsetDefaultLauncher();
    }
  }

  void _showTerminal(BuildContext context, AppState state) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => _TerminalSheet(state: state),
    );
  }

  void _showDebianTerminal(BuildContext context, AppState state) {
    _showTerminal(context, state);
    Future<void>.delayed(const Duration(milliseconds: 150), () {
      state.startDebianShell();
    });
  }
}

/// Simple terminal bottom sheet with command execution.
class _TerminalSheet extends StatefulWidget {
  final AppState state;
  const _TerminalSheet({required this.state});

  @override
  State<_TerminalSheet> createState() => _TerminalSheetState();
}

class _TerminalSheetState extends State<_TerminalSheet> {
  // Terminal panel stays dark in both themes for contrast.
  static const _panel = Color(0xFF0B0B0D);
  static const _panelRaised = Color(0xFF1C1C1E);
  static const _panelKey = Color(0xFF2C2C2E);
  static const _text = Color(0xFFE5E5EA);
  static const _muted = Color(0xFF8E8E93);
  static const _prompt = Color(0xFF30D158);

  /// Commands run this app session, newest last; shared across sheet openings.
  static final List<String> _history = [];

  final _controller = TextEditingController();
  final _focusNode = FocusNode();
  final _scrollController = ScrollController();
  int _historyIndex = -1;
  String _draft = '';
  String _lastText = '';
  bool _ctrlArmed = false;

  @override
  void initState() {
    super.initState();
    widget.state.addListener(_onStateChanged);
  }

  void _onStateChanged() {
    if (!mounted) return;
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 100),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  void dispose() {
    widget.state.removeListener(_onStateChanged);
    _controller.dispose();
    _focusNode.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _runCommand() async {
    final cmd = _controller.text.trim();
    // While a command runs, Enter answers it (an empty line accepts a default
    // such as apt's "[Y/n]"); otherwise empty input does nothing.
    if (cmd.isEmpty && !widget.state.isCommandRunning) return;
    if (cmd.isNotEmpty && (_history.isEmpty || _history.last != cmd)) {
      _history.add(cmd);
    }
    _historyIndex = -1;
    _setText('');
    await widget.state.executeCommand(cmd);
  }

  /// Copies the whole log, e.g. to paste install errors into a bug report.
  void _copyOutput() {
    Clipboard.setData(ClipboardData(text: widget.state.terminalOutput.join()));
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      const SnackBar(content: Text('Terminal output copied')),
    );
  }

  void _interrupt() {
    widget.state.interruptCommand();
    widget.state.appendTerminalOutput('\n^C\n');
  }

  // ── Input editing helpers ──

  void _setText(String text, {int? cursor}) {
    _lastText = text;
    _controller.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: cursor ?? text.length),
    );
  }

  int get _cursor {
    final offset = _controller.selection.baseOffset;
    return offset < 0 ? _controller.text.length : offset;
  }

  void _insert(String value) {
    final text = _controller.text;
    final at = _cursor;
    _setText(text.replaceRange(at, at, value), cursor: at + value.length);
  }

  void _moveCursor(int delta) {
    final next = (_cursor + delta).clamp(0, _controller.text.length);
    _controller.selection = TextSelection.collapsed(offset: next);
  }

  void _historyStep(int direction) {
    if (_history.isEmpty) return;
    if (_historyIndex == -1) {
      if (direction > 0) return;
      _draft = _controller.text;
      _historyIndex = _history.length - 1;
    } else {
      _historyIndex += direction;
    }
    if (_historyIndex >= _history.length) {
      _historyIndex = -1;
      _setText(_draft);
      return;
    }
    _historyIndex = _historyIndex.clamp(0, _history.length - 1);
    _setText(_history[_historyIndex]);
  }

  /// Completes the current line from the most recent matching history entry.
  void _complete() {
    final prefix = _controller.text;
    if (prefix.isEmpty) return;
    for (final cmd in _history.reversed) {
      if (cmd.startsWith(prefix) && cmd != prefix) {
        _setText(cmd);
        return;
      }
    }
  }

  /// Ctrl shortcuts: the next letter typed after tapping CTRL.
  void _ctrlShortcut(String letter) {
    switch (letter.toLowerCase()) {
      case 'c':
        _interrupt();
      case 'l':
        widget.state.clearTerminal();
      case 'u':
        _setText('');
      case 'a':
        _controller.selection = const TextSelection.collapsed(offset: 0);
      case 'e':
        _moveCursor(_controller.text.length);
      case 'd':
        if (_controller.text.isEmpty) _interrupt();
    }
  }

  void _onChanged(String text) {
    if (_ctrlArmed && text.length == _lastText.length + 1) {
      final at = (_controller.selection.baseOffset - 1).clamp(0, text.length - 1);
      final letter = text[at];
      setState(() => _ctrlArmed = false);
      _setText(_lastText, cursor: at);
      _ctrlShortcut(letter);
      return;
    }
    _lastText = text;
  }

  void _onKey(String key) {
    HapticFeedback.selectionClick();
    switch (key) {
      case 'ESC':
        _setText('');
      case 'TAB':
        _complete();
      case 'CTRL':
        setState(() => _ctrlArmed = !_ctrlArmed);
      case '↑':
        _historyStep(-1);
      case '↓':
        _historyStep(1);
      case '←':
        _moveCursor(-1);
      case '→':
        _moveCursor(1);
      case 'HOME':
        _controller.selection = const TextSelection.collapsed(offset: 0);
      case 'END':
        _moveCursor(_controller.text.length);
      default:
        _insert(key);
    }
    if (!_focusNode.hasFocus) _focusNode.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final runtime = state.isProotTerminal
        ? 'Debian PRoot'
        : state.hasRoot
            ? 'chroot · ${_distroLabel(state.installedDistro)}'
            : 'Native · Termux';

    return DraggableScrollableSheet(
      initialChildSize: 0.78,
      minChildSize: 0.35,
      maxChildSize: 0.95,
      expand: false,
      builder: (context, _) {
        return Container(
          decoration: BoxDecoration(
            color: DroidTheme.surface,
            borderRadius: const BorderRadius.vertical(
              top: Radius.circular(DroidTheme.radiusXl),
            ),
            border: Border(top: BorderSide(color: DroidTheme.surfaceBorder)),
          ),
          padding: EdgeInsets.only(
            bottom: MediaQuery.of(context).viewInsets.bottom,
          ),
          child: Column(
            children: [
              const SizedBox(height: 10),
              Container(
                width: 38,
                height: 5,
                decoration: BoxDecoration(
                  color: DroidTheme.surfaceBorder,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 14, 14, 12),
                child: Row(
                  children: [
                    Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: DroidTheme.secondary.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: const Icon(
                        Icons.terminal_rounded,
                        color: DroidTheme.secondary,
                        size: 21,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('Terminal', style: DroidTheme.headingSm),
                          Text(runtime, style: DroidTheme.bodySm),
                        ],
                      ),
                    ),
                    _headerButton(
                      icon: Icons.copy_all_rounded,
                      color: DroidTheme.textSecondary,
                      tooltip: 'Copy output',
                      onTap: _copyOutput,
                    ),
                    const SizedBox(width: 8),
                    _headerButton(
                      icon: Icons.cleaning_services_rounded,
                      color: DroidTheme.textSecondary,
                      tooltip: 'Clear (Ctrl+L)',
                      onTap: state.clearTerminal,
                    ),
                    const SizedBox(width: 8),
                    _headerButton(
                      icon: Icons.stop_rounded,
                      color: DroidTheme.error,
                      tooltip: 'Stop command (Ctrl+C)',
                      onTap: _interrupt,
                    ),
                  ],
                ),
              ),
              Expanded(
                child: Container(
                  margin: const EdgeInsets.symmetric(horizontal: 12),
                  decoration: BoxDecoration(
                    color: _panel,
                    borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
                    border: Border.all(color: const Color(0xFF2C2C2E)),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Column(
                    children: [
                      Expanded(
                        // One selection area so a drag can span many lines.
                        child: SelectionArea(
                          child: ListView.builder(
                            controller: _scrollController,
                            padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                            itemCount: state.terminalOutput.length,
                            itemBuilder: (context, index) {
                              final line = state.terminalOutput[index];
                              final isCommand = line.startsWith('\$');
                              return Text(
                                line,
                                style: DroidTheme.mono.copyWith(
                                  fontSize: 12.5,
                                  height: 1.45,
                                  color: isCommand ? _prompt : _text,
                                  fontWeight:
                                      isCommand ? FontWeight.w600 : FontWeight.w400,
                                ),
                              );
                            },
                          ),
                        ),
                      ),
                      _inputBar(),
                    ],
                  ),
                ),
              ),
              _keysBar(),
            ],
          ),
        );
      },
    );
  }

  Widget _headerButton({
    required IconData icon,
    required Color color,
    required String tooltip,
    required VoidCallback onTap,
  }) {
    return Tooltip(
      message: tooltip,
      child: Material(
        color: color.withValues(alpha: 0.12),
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: SizedBox(
            width: 38,
            height: 38,
            child: Icon(icon, size: 19, color: color),
          ),
        ),
      ),
    );
  }

  Widget _inputBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 6, 6, 6),
      decoration: const BoxDecoration(
        color: _panelRaised,
        border: Border(top: BorderSide(color: Color(0xFF2C2C2E))),
      ),
      child: Row(
        children: [
          Text(
            _ctrlArmed ? '^' : '\$',
            style: DroidTheme.mono.copyWith(
              color: _ctrlArmed ? DroidTheme.primary : _prompt,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: _controller,
              focusNode: _focusNode,
              onChanged: _onChanged,
              onSubmitted: (_) => _runCommand(),
              autofocus: true,
              autocorrect: false,
              enableSuggestions: false,
              keyboardType: TextInputType.visiblePassword,
              textInputAction: TextInputAction.send,
              cursorColor: _prompt,
              style: DroidTheme.mono.copyWith(fontSize: 13.5, color: Colors.white),
              decoration: InputDecoration(
                border: InputBorder.none,
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(vertical: 10),
                hintText: _ctrlArmed ? 'Ctrl + letter (C, L, U, A, E)' : 'Enter a command',
                hintStyle: DroidTheme.mono.copyWith(fontSize: 13, color: _muted),
              ),
            ),
          ),
          Material(
            color: DroidTheme.primary,
            shape: const CircleBorder(),
            child: InkWell(
              customBorder: const CircleBorder(),
              onTap: _runCommand,
              child: const SizedBox(
                width: 38,
                height: 38,
                child: Icon(Icons.arrow_upward_rounded, size: 20, color: Colors.white),
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// Esc/Tab/Ctrl/arrows/symbols row that sits just above the keyboard.
  Widget _keysBar() {
    const keys = [
      'ESC', 'TAB', 'CTRL', '↑', '↓', '←', '→', 'HOME', 'END',
      '-', '/', '|', '~', '>', '&', ';', '*', '\$',
    ];
    return Container(
      margin: const EdgeInsets.only(top: 8),
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom > 0
            ? 6
            : 6 + MediaQuery.of(context).padding.bottom,
      ),
      child: SizedBox(
        height: 42,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          itemCount: keys.length,
          separatorBuilder: (_, _) => const SizedBox(width: 6),
          itemBuilder: (context, index) {
            final key = keys[index];
            final active = key == 'CTRL' && _ctrlArmed;
            return GestureDetector(
              onTap: () => _onKey(key),
              child: Container(
                constraints: const BoxConstraints(minWidth: 44),
                padding: const EdgeInsets.symmetric(horizontal: 12),
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: active
                      ? DroidTheme.primary
                      : DroidTheme.isDark
                          ? _panelKey
                          : DroidTheme.surfaceLight,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  key,
                  style: DroidTheme.mono.copyWith(
                    fontSize: key.length > 1 ? 12 : 15,
                    fontWeight: FontWeight.w700,
                    color: active ? Colors.white : DroidTheme.textPrimary,
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  String _distroLabel(String distro) {
    switch (distro) {
      case 'ubuntu-chroot':
        return 'Ubuntu 24.04';
      case 'termux-native':
        return 'Termux';
      default:
        return distro;
    }
  }
}

// ── Small Action Card widget ──

class _ActionCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final Color color;
  final Gradient? gradient;
  final VoidCallback onTap;

  const _ActionCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.color,
    this.gradient,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
        child: Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            gradient: gradient != null
                ? LinearGradient(
                    colors: [
                      color.withValues(alpha: 0.18),
                      color.withValues(alpha: 0.08),
                    ],
                  )
                : null,
            color: gradient == null ? DroidTheme.cardBg : null,
            borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
            border: Border.all(
              color: gradient == null
                  ? DroidTheme.surfaceBorder
                  : color.withValues(alpha: 0.24),
            ),
          ),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(icon, color: color, size: 22),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: DroidTheme.headingSm),
                    Text(
                      subtitle,
                      style: DroidTheme.bodySm,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.chevron_right_rounded,
                color: DroidTheme.textDim,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SettingsSectionLabel extends StatelessWidget {
  final String text;

  const _SettingsSectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 10),
      child: Text(text.toUpperCase(), style: DroidTheme.label),
    );
  }
}

/// Rounded card that stacks [_SettingsRow]s with inset dividers.
class _SettingsGroup extends StatelessWidget {
  final List<Widget> children;

  const _SettingsGroup({required this.children});

  @override
  Widget build(BuildContext context) {
    final rows = <Widget>[];
    for (var i = 0; i < children.length; i++) {
      if (i > 0) {
        rows.add(Divider(
          height: 1,
          thickness: 1,
          indent: 62,
          color: DroidTheme.surfaceBorder,
        ));
      }
      rows.add(children[i]);
    }
    return Container(
      decoration: BoxDecoration(
        color: DroidTheme.cardBg,
        borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
        border: Border.all(color: DroidTheme.surfaceBorder),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(children: rows),
    );
  }
}

class _SettingsRow extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String title;
  final String subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _SettingsRow({
    required this.icon,
    required this.color,
    required this.title,
    required this.subtitle,
    this.onTap,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 13, 12, 13),
          child: Row(
            children: [
              Container(
                width: 34,
                height: 34,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: color, size: 19),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: DroidTheme.headingSm.copyWith(fontSize: 15)),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: DroidTheme.bodySm,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              trailing ??
                  Icon(
                    Icons.chevron_right_rounded,
                    color: DroidTheme.textDim,
                  ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Selectable theme card with a two-tone preview of the palette.
class _ThemeOption extends StatelessWidget {
  final String label;
  final IconData icon;
  final List<Color> preview;
  final bool selected;
  final VoidCallback onTap;

  const _ThemeOption({
    required this.label,
    required this.icon,
    required this.preview,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: selected
              ? DroidTheme.primary.withValues(alpha: 0.12)
              : DroidTheme.cardBg,
          borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
          border: Border.all(
            color: selected ? DroidTheme.primary : DroidTheme.surfaceBorder,
            width: selected ? 1.6 : 1,
          ),
        ),
        child: Column(
          children: [
            Container(
              height: 54,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: DroidTheme.surfaceBorder),
                gradient: LinearGradient(
                  colors: [preview[0], preview[0], preview[1], preview[1]],
                  stops: const [0, 0.5, 0.5, 1],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
              ),
              alignment: Alignment.center,
              child: Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.92),
                  shape: BoxShape.circle,
                ),
                child: Icon(icon, size: 17, color: const Color(0xFF1C1C1E)),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (selected) ...[
                  const Icon(
                    Icons.check_circle_rounded,
                    size: 15,
                    color: DroidTheme.primary,
                  ),
                  const SizedBox(width: 4),
                ],
                Text(
                  label,
                  style: DroidTheme.bodySm.copyWith(
                    color: selected ? DroidTheme.primary : DroidTheme.textSecondary,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Small rounded badge confirming a granted permission or completed step.
class _StatusPill extends StatelessWidget {
  final String label;
  final Color color;
  final IconData icon;

  const _StatusPill({
    required this.label,
    this.color = DroidTheme.accent,
    this.icon = Icons.check_circle_rounded,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(8, 5, 10, 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 4),
          Text(
            label,
            style: DroidTheme.label.copyWith(color: color, letterSpacing: 0.1),
          ),
        ],
      ),
    );
  }
}

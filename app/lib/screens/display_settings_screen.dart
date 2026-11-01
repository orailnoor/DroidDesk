import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:droiddesk/models/desktop_display.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';

class DisplaySettingsScreen extends StatefulWidget {
  const DisplaySettingsScreen({super.key});

  @override
  State<DisplaySettingsScreen> createState() => _DisplaySettingsScreenState();
}

class _DisplaySettingsScreenState extends State<DisplaySettingsScreen> {
  late final AppState _state;
  String? _selectedKey;
  DesktopDisplaySettings? _settings;
  String? _loadError;
  bool _loadingSettings = false;
  bool _dirty = false;
  int _settingsRevision = 0;
  String? _announcedError;

  @override
  void initState() {
    super.initState();
    _state = context.read<AppState>();
    _state.addListener(_displayChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      await _state.refreshDesktopDisplays();
      if (mounted) _displayChanged();
    });
  }

  @override
  void dispose() {
    _settingsRevision++;
    _state.removeListener(_displayChanged);
    super.dispose();
  }

  void _displayChanged() {
    if (!mounted) return;
    final error = _state.desktopDisplayError;
    if (error == null) {
      _announcedError = null;
    } else if (_announcedError != error) {
      _announcedError = error;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(error)));
        }
      });
    }
    final snapshot = _state.desktopDisplays;
    if (!snapshot.displays.any((display) => display.key == _selectedKey)) {
      final active = snapshot.displays.where(
        (display) => display.key == snapshot.activeDisplayKey,
      );
      final phone = snapshot.displays.where((display) => display.isDefault);
      setState(() {
        _selectedKey = active.isNotEmpty
            ? active.first.key
            : phone.firstOrNull?.key;
        _settings = null;
        _dirty = false;
        _loadingSettings = false;
        _settingsRevision++;
      });
    }
    if (!_dirty && !_loadingSettings && !_state.desktopDisplayBusy) {
      _loadSettings();
    }
  }

  Future<void> _loadSettings() async {
    final key = _selectedKey;
    if (key == null || !mounted) return;
    final revision = ++_settingsRevision;
    setState(() {
      _loadingSettings = true;
      _loadError = null;
    });
    try {
      final settings = await _state.getDesktopDisplaySettings(key);
      if (!mounted || _selectedKey != key || _settingsRevision != revision) {
        return;
      }
      setState(() {
        _settings = settings;
        _loadingSettings = false;
      });
    } on PlatformException catch (error) {
      _settingsFailed(key, revision, '${error.code}: ${error.message}');
    } on FormatException catch (error) {
      _settingsFailed(key, revision, error.toString());
    }
  }

  void _settingsFailed(String key, int revision, String message) {
    debugPrint('Display settings could not load: $message');
    if (!mounted || _selectedKey != key || _settingsRevision != revision) {
      return;
    }
    setState(() {
      _settings = null;
      _loadingSettings = false;
      _loadError = message;
    });
  }

  void _edit({DesktopRenderPolicy? render, String? scale, int? mode}) {
    final settings = _settings;
    if (settings == null) return;
    setState(() {
      _settings = DesktopDisplaySettings(
        displayKey: settings.displayKey,
        renderPolicy: render ?? settings.renderPolicy,
        uiScalePercent: scale == null
            ? settings.uiScalePercent
            : scale == 'auto'
            ? null
            : int.parse(scale),
        inputMode: mode ?? settings.inputMode,
      );
      _dirty = true;
    });
  }

  Future<void> _save() async {
    final settings = _settings;
    if (settings == null) return;
    await _state.saveDesktopDisplaySettings(settings);
    if (!mounted) return;
    if (_state.desktopOperationError == null) {
      setState(() {
        _dirty = false;
      });
      await _loadSettings();
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    final snapshot = state.desktopDisplays;
    final selected = snapshot.displays
        .where((display) => display.key == _selectedKey)
        .firstOrNull;
    final settings = _settings;
    final enabled =
        settings != null && !state.desktopDisplayBusy && !_loadingSettings;
    final error = state.desktopDisplayError ?? _loadError ?? state.errorMessage;
    return Scaffold(
      backgroundColor: DroidTheme.background,
      appBar: AppBar(
        title: const Text('Displays and scaling'),
        actions: [
          IconButton(
            tooltip: 'Refresh displays',
            onPressed: state.desktopDisplayBusy
                ? null
                : () async {
                    state.clearDesktopDisplayError();
                    await state.refreshDesktopDisplays();
                    if (mounted && !_dirty) await _loadSettings();
                  },
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(
            'One Linux session, one active view',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          const Text(
            'Connecting a monitor only updates this list. Choose a display and use Open on monitor to move the view.',
          ),
          const SizedBox(height: 16),
          if (error != null)
            Card(
              color: DroidTheme.error.withValues(alpha: 0.12),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(error),
              ),
            ),
          Text(
            'Applied render resolution',
            style: Theme.of(context).textTheme.labelLarge,
          ),
          Text(
            snapshot.renderWidth == null
                ? 'Unknown until a desktop window is attached'
                : '${snapshot.renderWidth} x ${snapshot.renderHeight}',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const Text(
            'X11 normally aligns framebuffer widths to 8-pixel units. The applied size is shown above.',
          ),
          Text(
            snapshot.profileKind == null
                ? 'No active profile'
                : 'Active profile: ${snapshot.profileKind!.name}',
          ),
          const SizedBox(height: 20),
          DropdownButtonFormField<String>(
            key: const Key('desktop-display-target'),
            initialValue: _selectedKey,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Display'),
            items: snapshot.displays
                .map(
                  (display) => DropdownMenuItem(
                    value: display.key,
                    enabled: display.eligible,
                    child: Text(
                      '${display.name} (display ${display.id})',
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                )
                .toList(),
            onChanged: state.desktopDisplayBusy
                ? null
                : (key) {
                    setState(() {
                      _selectedKey = key;
                      _settings = null;
                      _dirty = false;
                      _loadingSettings = false;
                    });
                    _loadSettings();
                  },
          ),
          const SizedBox(height: 10),
          if (snapshot.monitors.isEmpty)
            const Text('No independently launchable display detected'),
          if (selected != null && !selected.isDefault)
            const Text('Monitor preferences apply to all monitors.'),
          if (selected?.reason != null) Text(selected!.reason!),
          if (_loadingSettings) const LinearProgressIndicator(),
          const SizedBox(height: 20),
          if (settings != null) ...[
            DropdownButtonFormField<DesktopRenderPolicy>(
              key: ValueKey(
                'desktop-render-${settings.displayKey}-${settings.renderPolicy.name}',
              ),
              initialValue: settings.renderPolicy,
              decoration: const InputDecoration(labelText: 'Render policy'),
              items: DesktopRenderPolicy.values
                  .map(
                    (policy) => DropdownMenuItem(
                      value: policy,
                      child: Text(switch (policy) {
                        DesktopRenderPolicy.nativeResolution =>
                          'Native (X11-aligned)',
                        DesktopRenderPolicy.cap1080 => 'Cap at 1080p',
                        DesktopRenderPolicy.cap1440 => 'Cap at 1440p',
                        DesktopRenderPolicy.legacyPhone =>
                          'Legacy phone rendering',
                      }),
                    ),
                  )
                  .toList(),
              onChanged: enabled
                  ? (policy) {
                      if (policy != null) _edit(render: policy);
                    }
                  : null,
            ),
            const SizedBox(height: 14),
            DropdownButtonFormField<String>(
              key: const Key('desktop-ui-scale'),
              initialValue: settings.uiScalePercent?.toString() ?? 'auto',
              decoration: const InputDecoration(
                labelText: 'UI scale (independent of rendering)',
              ),
              items: [
                const DropdownMenuItem(value: 'auto', child: Text('Automatic')),
                ...DesktopDisplaySettings.uiScales.map(
                  (scale) =>
                      DropdownMenuItem(value: '$scale', child: Text('$scale%')),
                ),
              ],
              onChanged: enabled
                  ? (scale) {
                      if (scale != null) _edit(scale: scale);
                    }
                  : null,
            ),
            const SizedBox(height: 8),
            const Text(
              'Automatic keeps legacy phone defaults on the phone. Monitor defaults follow the current window. Some apps may need reopening after a DPI change.',
            ),
            const SizedBox(height: 14),
            DropdownButtonFormField<int>(
              key: ValueKey(
                'desktop-input-${settings.displayKey}-${settings.inputMode}',
              ),
              initialValue: settings.inputMode,
              decoration: const InputDecoration(labelText: 'Input mode'),
              items: const [
                DropdownMenuItem(value: 1, child: Text('Trackpad')),
                DropdownMenuItem(value: 2, child: Text('Touchscreen')),
                DropdownMenuItem(value: 3, child: Text('Direct touch')),
              ],
              onChanged: enabled
                  ? (mode) {
                      if (mode != null) _edit(mode: mode);
                    }
                  : null,
            ),
            const SizedBox(height: 16),
            FilledButton(
              key: const Key('desktop-save-settings'),
              onPressed: enabled ? _save : null,
              child: Text(
                state.desktopDisplayBusy ? 'Applying...' : 'Save settings',
              ),
            ),
          ],
          const SizedBox(height: 20),
          FilledButton.icon(
            key: const Key('desktop-open-monitor'),
            onPressed:
                !state.desktopDisplayBusy &&
                    state.isRunning &&
                    selected != null &&
                    !selected.isDefault &&
                    selected.eligible
                ? () => state.openDesktopOnDisplay(selected)
                : null,
            icon: const Icon(Icons.monitor),
            label: const Text('Open on monitor'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: !state.desktopDisplayBusy && state.isRunning
                ? state.moveDesktopToPhone
                : null,
            icon: const Icon(Icons.phone_android),
            label: const Text('Move to phone'),
          ),
          if (!state.isRunning)
            TextButton(
              onPressed: state.desktopDisplayBusy
                  ? null
                  : () => state.startLinux(),
              child: const Text('Launch desktop'),
            ),
          const SizedBox(height: 10),
          const Text(
            'A render cap trades sharpness for performance; it never changes your UI-scale override. Mirror-only firmware or missing video hardware cannot be changed by DroidDesk.',
          ),
        ],
      ),
    );
  }
}

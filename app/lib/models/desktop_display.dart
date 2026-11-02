enum DesktopDisplayPhase { hidden, attached, moving, stopped, error }

enum DesktopProfileKind { phone, wide, monitor }

enum DesktopRenderPolicy {
  nativeResolution('native'),
  cap1080('cap1080'),
  cap1440('cap1440'),
  legacyPhone('legacyPhone');

  const DesktopRenderPolicy(this.wireValue);
  final String wireValue;
}

class DesktopDisplayInfo {
  const DesktopDisplayInfo({
    required this.id,
    required this.key,
    required this.name,
    required this.isDefault,
    required this.eligible,
    this.reason,
  });

  factory DesktopDisplayInfo.fromMap(Map<Object?, Object?> map) {
    final id = _required<int>(map, 'id');
    if (id < 0) throw const FormatException('Invalid display id');
    return DesktopDisplayInfo(
      id: id,
      key: _required<String>(map, 'key'),
      name: _required<String>(map, 'name'),
      isDefault: _required<bool>(map, 'isDefault'),
      eligible: _required<bool>(map, 'eligible'),
      reason: _nullable<String>(map, 'reason'),
    );
  }

  final int id;
  final String key;
  final String name;
  final bool isDefault;
  final bool eligible;
  final String? reason;
}

class DesktopDisplayFailure {
  const DesktopDisplayFailure(this.code, this.message);
  factory DesktopDisplayFailure.fromMap(Map<Object?, Object?> map) =>
      DesktopDisplayFailure(
        _required<String>(map, 'code'),
        _required<String>(map, 'message'),
      );
  final String code;
  final String message;

  @override
  String toString() => '$code: $message';
}

class DesktopDisplayState {
  DesktopDisplayState({
    this.phase = DesktopDisplayPhase.hidden,
    this.autoOpenOnMonitor = true,
    this.activeDisplayId,
    this.activeDisplayKey,
    this.profileKind,
    this.renderWidth,
    this.renderHeight,
    List<DesktopDisplayInfo> displays = const [],
    this.error,
  }) : displays = List.unmodifiable(displays);

  factory DesktopDisplayState.fromMap(Map<Object?, Object?> map) {
    if (_required<int>(map, 'schemaVersion') != 1) {
      throw const FormatException('Unsupported desktop display schema');
    }
    final phase = _enum(DesktopDisplayPhase.values, map['phase'], 'phase');
    final width = _nullable<int>(map, 'renderWidth');
    final height = _nullable<int>(map, 'renderHeight');
    if ((width == null) != (height == null) ||
        (width != null && width <= 0) ||
        (height != null && height <= 0)) {
      throw const FormatException('Invalid applied desktop geometry');
    }
    final activeId = _nullable<int>(map, 'activeDisplayId');
    final activeKey = _nullable<String>(map, 'activeDisplayKey');
    final profile = _nullable<String>(map, 'profileKind');
    final error = _nullable<Object>(map, 'error');
    if ((activeId != null && activeId < 0) ||
        (activeId == null) != (activeKey == null) ||
        (phase == DesktopDisplayPhase.attached && activeId == null)) {
      throw const FormatException('Invalid active display');
    }
    final rawDisplays = _required<List<Object?>>(map, 'displays');
    final displays = rawDisplays
        .map((value) => DesktopDisplayInfo.fromMap(_map(value, 'display')))
        .toList();
    if (displays.map((display) => display.id).toSet().length !=
            displays.length ||
        displays.map((display) => display.key).toSet().length !=
            displays.length) {
      throw const FormatException('Duplicate desktop display identity');
    }
    return DesktopDisplayState(
      phase: phase,
      autoOpenOnMonitor: _required<bool>(map, 'autoOpenOnMonitor'),
      activeDisplayId: activeId,
      activeDisplayKey: activeKey,
      profileKind: profile == null
          ? null
          : _enum(DesktopProfileKind.values, profile, 'profileKind'),
      renderWidth: width,
      renderHeight: height,
      displays: displays,
      error: error == null
          ? null
          : DesktopDisplayFailure.fromMap(_map(error, 'error')),
    );
  }

  final DesktopDisplayPhase phase;
  final bool autoOpenOnMonitor;
  final int? activeDisplayId;
  final String? activeDisplayKey;
  final DesktopProfileKind? profileKind;
  final int? renderWidth;
  final int? renderHeight;
  final List<DesktopDisplayInfo> displays;
  final DesktopDisplayFailure? error;

  bool get isMoving => phase == DesktopDisplayPhase.moving;
  Iterable<DesktopDisplayInfo> get monitors =>
      displays.where((display) => !display.isDefault && display.eligible);
}

class DesktopDisplaySettings {
  DesktopDisplaySettings({
    required this.displayKey,
    required this.renderPolicy,
    required this.uiScalePercent,
    required this.inputMode,
  }) {
    if (displayKey.isEmpty ||
        (uiScalePercent != null && !uiScales.contains(uiScalePercent)) ||
        inputMode < 1 ||
        inputMode > 3) {
      throw ArgumentError('Invalid desktop display settings');
    }
  }

  factory DesktopDisplaySettings.fromMap(Map<Object?, Object?> map) {
    final scale = _nullable<int>(map, 'uiScalePercent');
    final mode = _required<int>(map, 'inputMode');
    if ((scale != null && !uiScales.contains(scale)) || mode < 1 || mode > 3) {
      throw const FormatException('Invalid desktop scale or input mode');
    }
    final policy = DesktopRenderPolicy.values.where(
      (policy) => policy.wireValue == map['renderPolicy'],
    );
    if (policy.isEmpty) throw const FormatException('Invalid render policy');
    return DesktopDisplaySettings(
      displayKey: _required<String>(map, 'displayKey'),
      renderPolicy: policy.single,
      uiScalePercent: scale,
      inputMode: mode,
    );
  }

  static const uiScales = [100, 125, 150, 175, 200];
  final String displayKey;
  final DesktopRenderPolicy renderPolicy;
  final int? uiScalePercent;
  final int inputMode;

  Map<String, Object?> toMap() => {
    'displayKey': displayKey,
    'renderPolicy': renderPolicy.wireValue,
    'uiScalePercent': uiScalePercent,
    'inputMode': inputMode,
  };
}

Map<Object?, Object?> desktopDisplayMap(Object? value) =>
    _map(value, 'desktop display payload');

Map<Object?, Object?> _map(Object? value, String field) {
  if (value is! Map<Object?, Object?>) {
    throw FormatException('Invalid $field');
  }
  return value;
}

T _required<T>(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value is! T || (value is String && value.isEmpty)) {
    throw FormatException('Invalid $field');
  }
  return value;
}

T? _nullable<T>(Map<Object?, Object?> map, String field) {
  if (!map.containsKey(field)) throw FormatException('Missing $field');
  return map[field] == null ? null : _required<T>(map, field);
}

T _enum<T extends Enum>(List<T> values, Object? value, String field) =>
    values.firstWhere(
      (item) => item.name == value,
      orElse: () => throw FormatException('Invalid $field'),
    );

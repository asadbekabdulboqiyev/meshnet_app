import 'package:permission_handler/permission_handler.dart';

/// Runtime permissions required for MeshNet to work.
///
/// Android 12+ requires runtime permissions for BLE (scan/connect/advertise)
/// and Wi-Fi Direct. Without them, connectGatt() throws SecurityException
/// and messages show as "failed" (red).
const List<Permission> _meshPermissions = [
  Permission.bluetoothScan,
  Permission.bluetoothConnect,
  Permission.bluetoothAdvertise,
  Permission.locationWhenInUse,
  Permission.nearbyWifiDevices,
  Permission.notification,
  Permission.microphone,
];

/// Requests all permissions. Returns the list of denied ones.
Future<List<Permission>> requestMeshPermissions() async {
  final denied = <Permission>[];
  for (final permission in _meshPermissions) {
    // Request each individually — if one is blocked, the rest still show.
    final status = await permission.request();
    if (!status.isGranted) {
      denied.add(permission);
    }
  }
  return denied;
}

/// Checks current status without requesting.
Future<List<Permission>> missingMeshPermissions() async {
  final denied = <Permission>[];
  for (final permission in _meshPermissions) {
    if (!await permission.isGranted) {
      denied.add(permission);
    }
  }
  return denied;
}

/// Opens system settings for denied permissions.
Future<void> openMeshAppSettings() => openAppSettings();

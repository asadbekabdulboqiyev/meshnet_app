import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// MeshNet — Flutter bridge to the Kotlin mesh engine.
/// Wrapper class around MethodChannel + EventChannel.
class MeshService {
  static const MethodChannel _method =
      MethodChannel('meshnet/engine');
  static const EventChannel _events =
      EventChannel('meshnet/events');

  final StreamController<Map<String, dynamic>> _eventController =
      StreamController<Map<String, dynamic>>.broadcast();

  Stream<Map<String, dynamic>> get events => _eventController.stream;

  bool _initialized = false;

  /// Starts listening to the event channel (when app opens).
  void connect() {
    if (_initialized) return;
    _events.receiveBroadcastStream().listen((raw) {
      try {
        final map = Map<String, dynamic>.from((raw as Map).cast());
        _eventController.add(map);
      } catch (_) {}
    });
    _initialized = true;
  }

  Future<bool> initEngine(String displayName) async {
    try {
      return await _method.invokeMethod('initEngine', {
        'displayName': displayName,
      }) == true;
    } catch (e) {
      return false;
    }
  }

  Future<bool> startNode() async {
    try {
      return await _method.invokeMethod('startNode') == true;
    } catch (e) {
      return false;
    }
  }

  Future<bool> stopNode() async {
    try {
      return await _method.invokeMethod('stopNode') == true;
    } catch (e) {
      return false;
    }
  }

  Future<Map<String, dynamic>?> getLocalIdentity() async {
    try {
      final result = await _method.invokeMethod('getLocalIdentity');
      if (result is Map) {
        return Map<String, dynamic>.from(result.cast());
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  Future<bool> pairWithPeer(String deviceId, String peerPublicKey) async {
    try {
      final result = await _method.invokeMethod('pairWithPeer', {
        'deviceId': deviceId,
        'peerPublicKey': peerPublicKey,
      });
      if (result is Map) {
        return result['status'] == 'paired';
      }
      return false;
    } catch (e) {
      return false;
    }
  }

  /// Find peer in network: FIND_PEER broadcast (route recovery).
  Future<bool> findPeer(String deviceId) async {
    try {
      final result = await _method.invokeMethod('findPeer', {
        'deviceId': deviceId,
      });
      if (result is Map) {
        return result['status'] == 'searching';
      }
      return false;
    } catch (e) {
      return false;
    }
  }

  Future<Map<String, dynamic>?> sendMessage(
      String targetDeviceId, String message) async {
    try {
      final result = await _method.invokeMethod('sendMessage', {
        'targetDeviceId': targetDeviceId,
        'message': message,
      });
      if (result is Map) {
        return Map<String, dynamic>.from(result.cast());
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  Future<List<Map<String, dynamic>>> getPeers() async {
    try {
      final result = await _method.invokeMethod('getPeers');
      if (result is List) {
        return result
            .map((e) => Map<String, dynamic>.from((e as Map).cast()))
            .toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  /// Node info + statistics (for topology debug screen).
  Future<Map<String, dynamic>?> getNodeInfo() async {
    try {
      final result = await _method.invokeMethod('getNodeInfo');
      if (result is Map) {
        return Map<String, dynamic>.from(result.cast());
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  Future<bool> clearPeer(String deviceId) async {
    try {
      return await _method.invokeMethod('clearPeer', {'deviceId': deviceId}) ==
          true;
    } catch (e) {
      return false;
    }
  }

  Future<String?> sendFile(String targetDeviceId, String filePath) async {
    final result = await _method.invokeMethod('sendFile', {
      'targetDeviceId': targetDeviceId,
      'filePath': filePath,
    });
    return result as String?;
  }

  Future<String?> sendImage(String targetDeviceId, String imagePath) async {
    final result = await _method.invokeMethod('sendImage', {
      'targetDeviceId': targetDeviceId,
      'imagePath': imagePath,
    });
    return result as String?;
  }

  Future<bool> cancelTransfer(String transferId) async {
    final result = await _method.invokeMethod('cancelTransfer', {
      'transferId': transferId,
    });
    return result == true;
  }

  Future<bool> startRecording() async {
    final result = await _method.invokeMethod('startRecording');
    return result == true;
  }

  Future<Map<String, dynamic>> stopRecording() async {
    final result = await _method.invokeMethod('stopRecording');
    return Map<String, dynamic>.from(result);
  }

  Future<String?> sendVoiceMessage(String targetDeviceId, String filePath, int durationMs) async {
    final result = await _method.invokeMethod('sendVoiceMessage', {
      'targetDeviceId': targetDeviceId,
      'filePath': filePath,
      'durationMs': durationMs,
    });
    return result as String?;
  }

  Future<Map<String, dynamic>?> createGroup(String name, List<String> memberDeviceIds) async {
    final result = await _method.invokeMethod('createGroup', {
      'name': name,
      'memberDeviceIds': memberDeviceIds,
    });
    return result != null ? Map<String, dynamic>.from(result) : null;
  }

  Future<List<Map<String, dynamic>>> getGroups() async {
    final result = await _method.invokeMethod('getGroups');
    return (result as List).map((g) => Map<String, dynamic>.from(g)).toList();
  }

  Future<bool> sendGroupMessage(String groupId, String message) async {
    final result = await _method.invokeMethod('sendGroupMessage', {
      'groupId': groupId,
      'message': message,
    });
    return result == true;
  }

  Future<Map<String, dynamic>?> getTopology() async {
    final result = await _method.invokeMethod('getTopology');
    return result != null ? Map<String, dynamic>.from(result) : null;
  }

  Future<int> markAsRead(String deviceId) async {
    try {
      final result = await _method.invokeMethod('markMessagesRead', {
        'deviceId': deviceId,
      });
      if (result is Map) {
        return result['marked'] as int? ?? 0;
      }
      return 0;
    } catch (e) {
      return 0;
    }
  }

  Future<Map<String, dynamic>> getUnreadCounts() async {
    try {
      final result = await _method.invokeMethod('getUnreadCounts');
      if (result is Map) {
        return Map<String, dynamic>.from(result.cast());
      }
      return {'total': 0, 'byDevice': {}};
    } catch (e) {
      return {'total': 0, 'byDevice': {}};
    }
  }
}

/// Riverpod global provider
final meshServiceProvider = Provider<MeshService>((ref) {
  final service = MeshService();
  ref.onDispose(() {
    service._eventController.close();
  });
  return service;
});
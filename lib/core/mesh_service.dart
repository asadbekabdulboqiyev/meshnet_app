import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:permission_handler/permission_handler.dart';
import 'permissions.dart' as perms;

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

  Future<List<Permission>> requestMeshPermissions() {
    return perms.requestMeshPermissions();
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

  // Voice message playback control
  Future<bool> playVoiceMessage(String messageId) async {
    try {
      final result = await _method.invokeMethod('playVoiceMessage', {
        'messageId': messageId,
      });
      return result == true;
    } catch (e) {
      return false;
    }
  }

  Future<bool> pauseVoiceMessage(String messageId) async {
    try {
      final result = await _method.invokeMethod('pauseVoiceMessage', {
        'messageId': messageId,
      });
      return result == true;
    } catch (e) {
      return false;
    }
  }

  Future<bool> setVoicePlaybackSpeed(String messageId, double speed) async {
    try {
      final result = await _method.invokeMethod('setVoicePlaybackSpeed', {
        'messageId': messageId,
        'speed': speed,
      });
      return result == true;
    } catch (e) {
      return false;
    }
  }

  Future<Map<String, dynamic>?> createGroup(String name, List<String> memberDeviceIds) async {
    try {
      final result = await _method.invokeMethod('createGroup', {
        'name': name,
        'memberDeviceIds': memberDeviceIds,
      });
      return result != null ? Map<String, dynamic>.from(result) : null;
    } catch (e) {
      return null;
    }
  }

  Future<List<Map<String, dynamic>>> getGroups() async {
    try {
      final result = await _method.invokeMethod('getGroups');
      if (result is List) {
        return result.map((g) => Map<String, dynamic>.from(g)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  Future<String?> sendGroupMessage(String groupId, String message) async {
    try {
      final result = await _method.invokeMethod('sendGroupMessage', {
        'groupId': groupId,
        'message': message,
      });
      return result as String?;
    } catch (e) {
      return null;
    }
  }

  Future<List<Map<String, dynamic>>> getGroupMessages(String groupId) async {
    try {
      final result = await _method.invokeMethod('getGroupMessages', {
        'groupId': groupId,
      });
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
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

  // ---------------- LocalNet (Phase 1) ----------------

  /// LocalNet holati: hostname, http port va ma'lum mesh hostlar.
  Future<Map<String, dynamic>?> getLocalNetInfo() async {
    try {
      final result = await _method.invokeMethod('getLocalNetInfo');
      if (result is Map) {
        return Map<String, dynamic>.from(result.cast());
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Hostname'ni mesh orqali topish (local cache, bo'lmasa DNS_QUERY flood).
  Future<Map<String, dynamic>?> resolveHost(String hostname) async {
    try {
      final result = await _method.invokeMethod('resolveHost', {
        'hostname': hostname,
      });
      if (result is Map) {
        return Map<String, dynamic>.from(result.cast());
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  // ---------------- LocalNet Files (Phase 2) ----------------

  /// Mahalliy faylni LocalNet'ga ulashish. Manifest qaytaradi.
  Future<Map<String, dynamic>?> shareLocalFile(String path) async {
    try {
      final result = await _method.invokeMethod('shareLocalFile', {
        'path': path,
      });
      if (result is Map) {
        return Map<String, dynamic>.from(result.cast());
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Ulangan fayllar ro'yxati (mahalliy manifestlar).
  Future<List<Map<String, dynamic>>> getSharedFiles() async {
    try {
      final result = await _method.invokeMethod('getSharedFiles');
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  /// Fayl ulashishni bekor qilish.
  Future<bool> unshareFile(String fileId) async {
    try {
      final result = await _method.invokeMethod('unshareFile', {
        'fileId': fileId,
      });
      return result == true;
    } catch (e) {
      return false;
    }
  }

  /// Masofadagi hostning fayllar ro'yxati (HTTP /files + manifestlar).
  Future<List<Map<String, dynamic>>> getHostFiles(String hostname) async {
    try {
      final result = await _method.invokeMethod('getHostFiles', {
        'hostname': hostname,
      });
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  /// Hostdan faylni yuklab olishni boshlash (chunkma-chunk, incremental).
  /// Returns true when the fetch was started. Progress va yakun `events`
  /// stream'dagi `fileSyncProgress` eventlari orqali keladi
  /// (state: started/progress/done/failed).
  Future<bool> fetchHostFile(String hostname, String fileId) async {
    try {
      final result = await _method.invokeMethod('fetchHostFile', {
        'hostname': hostname,
        'fileId': fileId,
      });
      return result is Map && result['started'] == true;
    } catch (e) {
      return false;
    }
  }

  // ---------------- LocalNet Collab (Phase 3) ----------------

  /// Board yaratish / ochish (mavjud bo'lsa holatini qaytaradi).
  Future<Map<String, dynamic>?> createBoard(String roomId) async {
    try {
      final result = await _method.invokeMethod('createBoard', {'roomId': roomId});
      if (result is Map) return Map<String, dynamic>.from(result.cast());
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Chizg'ich (stroke) yuborish: points = [[x,y], [x,y], ...].
  Future<String?> sendStroke(
    String roomId,
    int color,
    double width,
    List<List<double>> points,
  ) async {
    try {
      final result = await _method.invokeMethod('sendStroke', {
        'roomId': roomId,
        'color': color,
        'width': width,
        'points': points,
      });
      return result is Map ? result['strokeId'] as String? : null;
    } catch (e) {
      return null;
    }
  }

  /// Board holatini olish (barcha stroke'lar).
  Future<Map<String, dynamic>?> getBoard(String roomId) async {
    try {
      final result = await _method.invokeMethod('getBoard', {'roomId': roomId});
      if (result is Map) return Map<String, dynamic>.from(result.cast());
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Boardni tozalash (hammeshga broadcast).
  Future<bool> clearBoard(String roomId) async {
    try {
      final result = await _method.invokeMethod('clearBoard', {'roomId': roomId});
      return result is Map && result['cleared'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Hujjat yaratish / ochish.
  Future<Map<String, dynamic>?> createDoc(String docId, String title) async {
    try {
      final result = await _method.invokeMethod('createDoc', {
        'docId': docId,
        'title': title,
      });
      if (result is Map) return Map<String, dynamic>.from(result.cast());
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Hujjat matnini saqlash (rev +1, meshga broadcast).
  Future<int?> editDoc(String docId, String text) async {
    try {
      final result = await _method.invokeMethod('editDoc', {
        'docId': docId,
        'text': text,
      });
      return result is Map ? result['rev'] as int? : null;
    } catch (e) {
      return null;
    }
  }

  /// Hujjat holatini olish.
  Future<Map<String, dynamic>?> getDoc(String docId) async {
    try {
      final result = await _method.invokeMethod('getDoc', {'docId': docId});
      if (result is Map) return Map<String, dynamic>.from(result.cast());
      return null;
    } catch (e) {
      return null;
    }
  }

  /// So'rovnoma yaratish (kamida 2 variant).
  Future<Map<String, dynamic>?> createPoll(String question, List<String> options) async {
    try {
      final result = await _method.invokeMethod('createPoll', {
        'question': question,
        'options': options,
      });
      if (result is Map) return Map<String, dynamic>.from(result.cast());
      return null;
    } catch (e) {
      return null;
    }
  }

  /// So'rovnoma uchun ovoz berish.
  Future<bool> votePoll(String pollId, int optionIndex) async {
    try {
      final result = await _method.invokeMethod('votePoll', {
        'pollId': pollId,
        'optionIndex': optionIndex,
      });
      return result is Map && result['accepted'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Barcha so'rovnoma + natijalar.
  Future<List<Map<String, dynamic>>> getPolls() async {
    try {
      final result = await _method.invokeMethod('getPolls');
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  // ---------------- LocalNet App Distribution (Phase 4) ----------------

  /// Biz share qilgan APK'lar (metadata bilan).
  Future<List<Map<String, dynamic>>> getLocalApps() async {
    try {
      final result = await _method.invokeMethod('getLocalApps');
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  /// Masofadagi hostning APK'lari (metadata manifestdan — package info
  /// yuklab olinguncha noma'lum).
  Future<List<Map<String, dynamic>>> getHostApps(String hostname) async {
    try {
      final result = await _method.invokeMethod('getHostApps', {
        'hostname': hostname,
      });
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  /// Yuklab olingan APK'ni tizim o'rnatuvchiga uzatish.
  Future<bool> installApk(String fileId) async {
    try {
      final result = await _method.invokeMethod('installApk', {'fileId': fileId});
      return result is Map && result['launched'] == true;
    } catch (e) {
      return false;
    }
  }

  // ---------------- Internet Gateway (Phase 5) ----------------

  /// O'z internetimizni mesh tarmoqqa ochish (HTTP/CONNECT proxy).
  Future<Map<String, dynamic>> startInternetGateway({int port = 0}) async {
    try {
      final result = await _method.invokeMethod('startInternetGateway', {'port': port});
      if (result is Map) return Map<String, dynamic>.from(result);
      return {'running': false};
    } catch (e) {
      return {'running': false, 'error': e.toString()};
    }
  }

  /// Gateway'ni o'chirish.
  Future<bool> stopInternetGateway() async {
    try {
      final result = await _method.invokeMethod('stopInternetGateway');
      return result is Map && result['running'] == false;
    } catch (e) {
      return false;
    }
  }

  /// Barcha ma'lum gateway'lar (o'zimiz + masofadagilar).
  Future<List<Map<String, dynamic>>> getGateways() async {
    try {
      final result = await _method.invokeMethod('getGateways');
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  /// Gateway sog'ligini tekshirish (health probe + latency).
  Future<Map<String, dynamic>?> testGateway(String hostname) async {
    try {
      final result = await _method.invokeMethod('testGateway', {'hostname': hostname});
      if (result is Map) return Map<String, dynamic>.from(result);
      return null;
    } catch (e) {
      return null;
    }
  }

  // ---------------- Phase 6: Emergency Broadcast ----------------

  /// Emergency alert yuborish (flood, high priority).
  Future<Map<String, dynamic>?> sendEmergencyAlert({
    required int level, // 1=INFO, 2=WARNING, 3=CRITICAL, 4=EMERGENCY
    required String title,
    required String message,
    String? location,
    String? coordinates,
    int ttlMinutes = 60,
    bool requiresAck = true,
  }) async {
    try {
      final result = await _method.invokeMethod('sendEmergencyAlert', {
        'level': level,
        'title': title,
        'message': message,
        'location': ?location,
        'coordinates': ?coordinates,
        'ttlMinutes': ttlMinutes,
        'requiresAck': requiresAck,
      });
      if (result is Map) return Map<String, dynamic>.from(result);
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Emergency alert'ni tasdiqlash (ack).
  Future<bool> acknowledgeEmergency(String alertId) async {
    try {
      final result = await _method.invokeMethod('acknowledgeEmergency', {'alertId': alertId});
      return result is Map && result['acknowledged'] == true;
    } catch (e) {
      return false;
    }
  }

  /// O'z emergency alert'imizni bekor qilish.
  Future<bool> cancelEmergency(String alertId) async {
    try {
      final result = await _method.invokeMethod('cancelEmergency', {'alertId': alertId});
      return result is Map && result['cancelled'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Faol emergency alert'lar ro'yxati.
  Future<List<Map<String, dynamic>>> getEmergencies() async {
    try {
      final result = await _method.invokeMethod('getEmergencies');
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  // ---------------- Phase 6: RBAC ----------------

  /// Device rolini o'rnatish (mesh-wide).
  Future<bool> setDeviceRole(String deviceId, String role) async {
    try {
      final result = await _method.invokeMethod('setDeviceRole', {'deviceId': deviceId, 'role': role});
      return result is Map && result['ok'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Device rolini olish.
  Future<Map<String, dynamic>?> getDeviceRole(String deviceId) async {
    try {
      final result = await _method.invokeMethod('getDeviceRole', {'deviceId': deviceId});
      if (result is Map) return Map<String, dynamic>.from(result);
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Resource uchun role o'rnatish.
  Future<bool> setResourceRole({
    required String resourceType,
    required String resourceId,
    required String deviceId,
    required String role,
  }) async {
    try {
      final result = await _method.invokeMethod('setResourceRole', {
        'resourceType': resourceType,
        'resourceId': resourceId,
        'deviceId': deviceId,
        'role': role,
      });
      return result is Map && result['ok'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Permission tekshirish.
  Future<bool> checkPermission({
    required String deviceId,
    required String permission,
    String? resourceType,
    String? resourceId,
  }) async {
    try {
      final result = await _method.invokeMethod('checkPermission', {
        'deviceId': deviceId,
        'permission': permission,
        'resourceType': ?resourceType,
        'resourceId': ?resourceId,
      });
      return result is Map && result['hasPermission'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Device'ni ban qilish.
  Future<bool> banDevice(String deviceId) async {
    try {
      final result = await _method.invokeMethod('banDevice', {'deviceId': deviceId});
      return result is Map && result['banned'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Device ban'ini olish.
  Future<bool> unbanDevice(String deviceId) async {
    try {
      final result = await _method.invokeMethod('unbanDevice', {'deviceId': deviceId});
      return result is Map && result['unbanned'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Xabarlar ichida qidiruv (lokal bazada).
  Future<List<Map<String, dynamic>>> searchMessages({
    required String query,
    String? deviceId,
    int limit = 50,
  }) async {
    try {
      final result = await _method.invokeMethod('searchMessages', {
        'query': query,
        'deviceId': ?deviceId,
        'limit': limit,
      });
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }
  // ---------------- Phase 6: Mesh-wide Search ----------------

  /// Mahalliy search (indexda).
  Future<List<Map<String, dynamic>>> searchLocal({
    required List<String> terms,
    Set<String> resourceTypes = const {},
    int maxResults = 20,
  }) async {
    try {
      final result = await _method.invokeMethod('searchLocal', {
        'terms': terms,
        'resourceTypes': resourceTypes.toList(),
        'maxResults': maxResults,
      });
      if (result is List) {
        return result.map((m) => Map<String, dynamic>.from(m)).toList();
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  /// Tarmoq boyicha distributed search.
  Future<String?> searchDistributed({
    required List<String> terms,
    Set<String> resourceTypes = const {},
    int maxResults = 20,
  }) async {
    try {
      final result = await _method.invokeMethod('searchDistributed', {
        'terms': terms,
        'resourceTypes': resourceTypes.toList(),
        'maxResults': maxResults,
      });
      if (result is Map) return result['queryId'] as String?;
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Contentni indexga qo'shish.
  Future<String?> indexContent({
    required String resourceType,
    required String resourceId,
    required String title,
    required String content,
    List<String> tags = const [],
    Map<String, String> metadata = const {},
  }) async {
    try {
      final result = await _method.invokeMethod('indexContent', {
        'resourceType': resourceType,
        'resourceId': resourceId,
        'title': title,
        'content': content,
        'tags': tags,
        'metadata': metadata,
      });
      if (result is Map) return result['docId'] as String?;
      return null;
    } catch (e) {
      return null;
    }
  }

  /// Indexdan olib tashlash.
  Future<bool> removeFromIndex(String docId) async {
    try {
      final result = await _method.invokeMethod('removeFromIndex', {'docId': docId});
      return result is Map && result['removed'] == true;
    } catch (e) {
      return false;
    }
  }

  /// Search statistikasi.
  Future<Map<String, dynamic>?> getSearchStats() async {
    try {
      final result = await _method.invokeMethod('getSearchStats');
      if (result is Map) return Map<String, dynamic>.from(result);
      return null;
    } catch (e) {
      return null;
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
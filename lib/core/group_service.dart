import 'package:flutter/services.dart';
import '../models/group_model.dart';

class GroupService {
  static const MethodChannel _method = MethodChannel('meshnet/group');

  Future<MeshGroup> createGroup(String name, List<String> memberDeviceIds) async {
    final result = await _method.invokeMethod('createGroup', {
      'name': name,
      'memberDeviceIds': memberDeviceIds,
    });
    return MeshGroup.fromMap(Map<String, dynamic>.from(result));
  }

  Future<List<MeshGroup>> getGroups() async {
    final result = await _method.invokeMethod('getGroups');
    return (result as List)
        .map((g) => MeshGroup.fromMap(Map<String, dynamic>.from(g)))
        .toList();
  }

  Future<MeshGroup?> getGroupInfo(String groupId) async {
    final result = await _method.invokeMethod('getGroupInfo', {
      'groupId': groupId,
    });
    if (result == null) return null;
    return MeshGroup.fromMap(Map<String, dynamic>.from(result));
  }

  Future<bool> sendGroupMessage(String groupId, String message) async {
    final result = await _method.invokeMethod('sendGroupMessage', {
      'groupId': groupId,
      'message': message,
    });
    return result == true;
  }

  Future<bool> addMember(String groupId, String deviceId, String displayName) async {
    final result = await _method.invokeMethod('addMember', {
      'groupId': groupId,
      'deviceId': deviceId,
      'displayName': displayName,
    });
    return result == true;
  }

  Future<bool> removeMember(String groupId, String deviceId) async {
    final result = await _method.invokeMethod('removeMember', {
      'groupId': groupId,
      'deviceId': deviceId,
    });
    return result == true;
  }

  Future<bool> leaveGroup(String groupId) async {
    final result = await _method.invokeMethod('leaveGroup', {
      'groupId': groupId,
    });
    return result == true;
  }
}

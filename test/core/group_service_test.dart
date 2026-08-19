import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshnet_app/core/group_service.dart';
import 'package:meshnet_app/models/group_model.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('GroupService', () {
    late GroupService service;
    const channel = MethodChannel('meshnet/group');
    late List<MethodCall> log;

    setUp(() {
      log = [];
      service = GroupService();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        log.add(methodCall);
        switch (methodCall.method) {
          case 'createGroup':
            return {
              'groupId': 'grp-1',
              'name': methodCall.arguments['name'],
              'members': [
                {
                  'deviceId': 'creator',
                  'displayName': 'Admin',
                  'role': 'admin',
                },
              ],
              'createdAtMs': 1700000000000,
              'createdBy': 'creator',
            };
          case 'getGroups':
            return [
              {
                'groupId': 'grp-1',
                'name': 'Test Group',
                'members': [],
                'createdAtMs': 1700000000000,
                'createdBy': 'creator',
              },
            ];
          case 'getGroupInfo':
            return {
              'groupId': methodCall.arguments['groupId'],
              'name': 'Info Group',
              'members': [],
              'createdAtMs': 1700000000000,
              'createdBy': 'creator',
            };
          case 'sendGroupMessage':
            return true;
          case 'addMember':
            return true;
          case 'removeMember':
            return true;
          case 'leaveGroup':
            return true;
          default:
            return null;
        }
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('createGroup sends correct arguments and returns MeshGroup', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        log.add(methodCall);
        if (methodCall.method == 'createGroup') {
          return {
            'groupId': 'grp-1',
            'name': methodCall.arguments['name'],
            'members': [],
            'createdAtMs': 1700000000000,
            'createdBy': 'creator',
          };
        }
        return null;
      });

      final group = await service.createGroup('My Group', ['dev-1', 'dev-2']);
      expect(group, isA<MeshGroup>());
      expect(group.groupId, 'grp-1');
      expect(group.name, 'My Group');
      expect(group.members, isEmpty);
      expect(log.last.method, 'createGroup');
      expect(log.last.arguments['name'], 'My Group');
      expect(log.last.arguments['memberDeviceIds'], ['dev-1', 'dev-2']);
    });

    test('getGroups returns list of MeshGroup', () async {
      final groups = await service.getGroups();
      expect(groups.length, 1);
      expect(groups[0].groupId, 'grp-1');
      expect(groups[0].name, 'Test Group');
    });

    test('getGroupInfo returns MeshGroup for valid id', () async {
      final group = await service.getGroupInfo('grp-1');
      expect(group, isNotNull);
      expect(group!.groupId, 'grp-1');
      expect(group.name, 'Info Group');
    });

    test('getGroupInfo returns null when not found', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        return null;
      });

      final result = await service.getGroupInfo('unknown');
      expect(result, isNull);
    });

    test('sendGroupMessage returns true on success', () async {
      final result = await service.sendGroupMessage('grp-1', 'Hello group!');
      expect(result, isTrue);
      expect(log.last.method, 'sendGroupMessage');
      expect(log.last.arguments['groupId'], 'grp-1');
      expect(log.last.arguments['message'], 'Hello group!');
    });

    test('sendGroupMessage throws on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(
        () => service.sendGroupMessage('grp-1', 'msg'),
        throwsA(isA<PlatformException>()),
      );
    });

    test('addMember returns true on success', () async {
      final result = await service.addMember('grp-1', 'dev-2', 'New User');
      expect(result, isTrue);
      expect(log.last.method, 'addMember');
      expect(log.last.arguments['groupId'], 'grp-1');
      expect(log.last.arguments['deviceId'], 'dev-2');
      expect(log.last.arguments['displayName'], 'New User');
    });

    test('removeMember returns true on success', () async {
      final result = await service.removeMember('grp-1', 'dev-2');
      expect(result, isTrue);
      expect(log.last.method, 'removeMember');
      expect(log.last.arguments['deviceId'], 'dev-2');
    });

    test('leaveGroup returns true on success', () async {
      final result = await service.leaveGroup('grp-1');
      expect(result, isTrue);
      expect(log.last.method, 'leaveGroup');
      expect(log.last.arguments['groupId'], 'grp-1');
    });

    test('leaveGroup throws on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(
        () => service.leaveGroup('grp-1'),
        throwsA(isA<PlatformException>()),
      );
    });

    test('addMember throws on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(
        () => service.addMember('g', 'd', 'n'),
        throwsA(isA<PlatformException>()),
      );
    });

    test('removeMember throws on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(
        () => service.removeMember('g', 'd'),
        throwsA(isA<PlatformException>()),
      );
    });
  });
}

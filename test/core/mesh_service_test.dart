import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:meshnet_app/core/mesh_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('MeshService', () {
    late MeshService service;
    late List<MethodCall> log;
    const channel = MethodChannel('meshnet/engine');

    setUp(() {
      log = [];
      service = MeshService();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        log.add(methodCall);
        switch (methodCall.method) {
          case 'initEngine':
            return true;
          case 'startNode':
            return true;
          case 'stopNode':
            return true;
          case 'getLocalIdentity':
            return {'deviceId': 'test-id', 'publicKey': 'pub-key', 'displayName': 'Test'};
          case 'pairWithPeer':
            return {'status': 'paired'};
          case 'findPeer':
            return {'status': 'searching'};
          case 'sendMessage':
            return {'messageId': 'msg-1', 'status': 'sent'};
          case 'getPeers':
            return [
              {'deviceId': 'peer-1', 'displayName': 'Peer1'},
            ];
          case 'getNodeInfo':
            return {
              'deviceId': 'test-id',
              'running': true,
              'peers': 1,
              'messagesSent': 5,
            };
          case 'clearPeer':
            return true;
          case 'getGroups':
            return [];
          case 'getTopology':
            return {'nodes': [], 'edges': []};
          default:
            return null;
        }
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('initEngine sends correct method call', () async {
      final result = await service.initEngine('Test User');
      expect(result, isTrue);
      expect(log.length, 1);
      expect(log[0].method, 'initEngine');
      expect(log[0].arguments['displayName'], 'Test User');
    });

    test('initEngine returns false on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error', message: 'failed');
      });

      final result = await service.initEngine('Test');
      expect(result, isFalse);
    });

    test('startNode sends correct method call', () async {
      final result = await service.startNode();
      expect(result, isTrue);
      expect(log.last.method, 'startNode');
    });

    test('startNode returns false on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.startNode();
      expect(result, isFalse);
    });

    test('stopNode sends correct method call', () async {
      final result = await service.stopNode();
      expect(result, isTrue);
      expect(log.last.method, 'stopNode');
    });

    test('stopNode returns false on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.stopNode();
      expect(result, isFalse);
    });

    test('getLocalIdentity returns identity map', () async {
      final identity = await service.getLocalIdentity();
      expect(identity, isNotNull);
      expect(identity!['deviceId'], 'test-id');
      expect(identity['publicKey'], 'pub-key');
      expect(identity['displayName'], 'Test');
    });

    test('getLocalIdentity returns null on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.getLocalIdentity();
      expect(result, isNull);
    });

    test('pairWithPeer returns true when paired', () async {
      final result = await service.pairWithPeer('device-1', 'pub-key-1');
      expect(result, isTrue);
      expect(log.last.method, 'pairWithPeer');
      expect(log.last.arguments['deviceId'], 'device-1');
    });

    test('pairWithPeer returns false on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.pairWithPeer('device-1', 'key');
      expect(result, isFalse);
    });

    test('findPeer returns true when searching', () async {
      final result = await service.findPeer('device-1');
      expect(result, isTrue);
      expect(log.last.method, 'findPeer');
    });

    test('findPeer returns false on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.findPeer('device-1');
      expect(result, isFalse);
    });

    test('sendMessage returns result map', () async {
      final result = await service.sendMessage('target-1', 'hello');
      expect(result, isNotNull);
      expect(result!['messageId'], 'msg-1');
      expect(log.last.method, 'sendMessage');
    });

    test('sendMessage returns null on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.sendMessage('target', 'msg');
      expect(result, isNull);
    });

    test('getPeers returns peer list', () async {
      final peers = await service.getPeers();
      expect(peers.length, 1);
      expect(peers[0]['deviceId'], 'peer-1');
    });

    test('getPeers returns empty on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.getPeers();
      expect(result, isEmpty);
    });

    test('getNodeInfo returns node info', () async {
      final info = await service.getNodeInfo();
      expect(info, isNotNull);
      expect(info!['deviceId'], 'test-id');
      expect(info['running'], isTrue);
    });

    test('getNodeInfo returns null on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.getNodeInfo();
      expect(result, isNull);
    });

    test('clearPeer returns true on success', () async {
      final result = await service.clearPeer('device-1');
      expect(result, isTrue);
    });

    test('clearPeer returns false on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final result = await service.clearPeer('device-1');
      expect(result, isFalse);
    });

    test('getGroups returns list', () async {
      final groups = await service.getGroups();
      expect(groups, isEmpty);
    });

    test('getTopology returns topology map', () async {
      final topology = await service.getTopology();
      expect(topology, isNotNull);
      expect(topology!['nodes'], isEmpty);
    });

    test('getTopology throws on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(
        () => service.getTopology(),
        throwsA(isA<PlatformException>()),
      );
    });

    test('connect only initializes once', () {
      service.connect();
      service.connect(); // second call should be no-op
    });
  });

  group('meshServiceProvider', () {
    test('creates MeshService instance', () {
      final container = ProviderContainer();
      final service = container.read(meshServiceProvider);
      expect(service, isA<MeshService>());
      container.dispose();
    });

    test('returns same instance on multiple reads', () {
      final container = ProviderContainer();
      final s1 = container.read(meshServiceProvider);
      final s2 = container.read(meshServiceProvider);
      expect(s1, same(s2));
      container.dispose();
    });
  });
}

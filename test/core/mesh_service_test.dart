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
          case 'getLocalNetInfo':
            return {
              'available': true,
              'hostname': 'test-node',
              'fqdn': 'test-node.mesh',
              'httpPort': 8080,
              'hosts': [
                {'hostname': 'test-node', 'deviceId': 'test-id', 'isSelf': true},
              ],
            };
          case 'resolveHost':
            return {
              'found': false,
              'hostname': methodCall.arguments['hostname'],
              'deviceId': '',
              'pendingQuery': true,
            };
          case 'shareLocalFile':
            return {
              'fileId': 'abc123',
              'name': 'notes.txt',
              'fileSize': 2048,
              'chunkCount': 1,
            };
          case 'getSharedFiles':
            return [
              {'fileId': 'abc123', 'name': 'notes.txt', 'chunkCount': 1},
            ];
          case 'unshareFile':
            return true;
          case 'getHostFiles':
            return [
              {'fileId': 'def456', 'name': 'photo.jpg', 'fileSize': 9999, 'chunkCount': 3},
            ];
          case 'fetchHostFile':
            return {'started': true};
          case 'getLocalApps':
            return [
              {
                'fileId': 'app789',
                'fileName': 'meshdemo.apk',
                'fileSize': 5242880,
                'packageName': 'com.techcorp.meshdemo',
                'versionName': '1.4.2',
                'versionCode': 14,
                'senderDeviceId': 'test-id',
                'hasPackageInfo': true,
              },
            ];
          case 'getHostApps':
            return [
              {
                'fileId': 'rem001',
                'fileName': 'remote-app.apk',
                'fileSize': 3145728,
                'packageName': null,
                'versionName': null,
                'versionCode': null,
                'senderDeviceId': 'peer-1',
                'hasPackageInfo': false,
              },
            ];
          case 'installApk':
            return {'launched': true};
          case 'createBoard':
            return {'roomId': methodCall.arguments['roomId'], 'strokeCount': 0};
          case 'sendStroke':
            return {'strokeId': 'stroke-1'};
          case 'getBoard':
            return {
              'roomId': methodCall.arguments['roomId'],
              'exists': true,
              'strokes': [
                {
                  'strokeId': 's1',
                  'authorId': 'dev-a',
                  'color': 4294901760,
                  'width': 3.0,
                  'points': [
                    [1.0, 2.0],
                    [3.0, 4.0],
                  ],
                },
              ],
            };
          case 'clearBoard':
            return {'cleared': true};
          case 'createDoc':
            return {'docId': methodCall.arguments['docId'], 'title': 'Team Notes', 'rev': 0, 'text': ''};
          case 'editDoc':
            return {'docId': methodCall.arguments['docId'], 'rev': 1};
          case 'getDoc':
            return {'docId': methodCall.arguments['docId'], 'title': 'Team Notes', 'rev': 2, 'text': 'salom'};
          case 'createPoll':
            return {'pollId': 'p1', 'question': methodCall.arguments['question'], 'options': ['ha', "yo'q"]};
          case 'votePoll':
            return {'accepted': true};
          case 'getPolls':
            return [
              {
                'pollId': 'p1',
                'question': 'Tayyormisiz?',
                'options': ['ha', "yo'q"],
                'tally': {0: 2},
                'totalVotes': 2,
              },
            ];
          case 'startInternetGateway':
            return {'running': true, 'port': methodCall.arguments['port'] ?? 8081};
          case 'stopInternetGateway':
            return {'running': false};
          case 'getGateways':
            return [
              {
                'hostname': 'gw1',
                'deviceId': 'dev-1',
                'proxyPort': 8081,
                'isSelf': true,
                'activeTunnels': 2,
                'totalConnections': 5,
                'bytesToTarget': 1024,
                'bytesFromTarget': 2048,
                'denied': 0,
              },
            ];
          case 'testGateway':
            return {
              'reachable': true,
              'hostname': methodCall.arguments['hostname'] ?? 'gw1',
              'proxyPort': 8081,
              'latencyMs': 42,
            };
          case 'sendEmergencyAlert':
            return {'alertId': 'emg_123', 'senderId': 'test-id', 'level': 'WARNING', 'title': 'Test', 'message': 'Test message', 'expiresAtMs': 9999999999999};
          case 'acknowledgeEmergency':
            return {'acknowledged': true};
          case 'cancelEmergency':
            return {'cancelled': true};
          case 'getEmergencies':
            return [
              {'alertId': 'e1', 'level': 'CRITICAL', 'title': 'Fire', 'message': 'Building on fire', 'senderId': 'dev-1', 'expiresAtMs': 9999999999999},
            ];
          case 'setDeviceRole':
            return {'ok': true};
          case 'getDeviceRole':
            return {'deviceId': 'dev-1', 'role': 'ADMIN'};
          case 'setResourceRole':
            return {'ok': true};
          case 'checkPermission':
            return {'hasPermission': true};
          case 'banDevice':
            return {'banned': true};
          case 'unbanDevice':
            return {'unbanned': true};
          case 'searchLocal':
            return [
              {'docId': 'doc-1', 'resourceType': 'doc', 'title': 'Meeting Notes', 'snippet': '...discuss budget...', 'score': 2.0},
            ];
          case 'searchDistributed':
            return {'queryId': 'qry-123'};
          case 'indexContent':
            return {'docId': 'doc_123456789_1234'};
          case 'removeFromIndex':
            return {'removed': true};
          case 'getSearchStats':
            return {'documents': 42, 'terms': 128, 'pendingQueries': 0};
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

    test('getLocalNetInfo returns info map', () async {
      final info = await service.getLocalNetInfo();
      expect(info, isNotNull);
      expect(info!['hostname'], 'test-node');
      expect(info['httpPort'], 8080);
      expect((info['hosts'] as List), hasLength(1));
    });

    test('resolveHost returns resolution result', () async {
      final result = await service.resolveHost('ghost');
      expect(result, isNotNull);
      expect(result!['found'], isFalse);
      expect(result['pendingQuery'], isTrue);
      expect(result['hostname'], 'ghost');
    });

    test('shareLocalFile returns manifest map', () async {
      final manifest = await service.shareLocalFile('/tmp/notes.txt');
      expect(manifest, isNotNull);
      expect(manifest!['fileId'], 'abc123');
      expect(manifest['name'], 'notes.txt');
      expect(log.last.method, 'shareLocalFile');
      expect(log.last.arguments['path'], '/tmp/notes.txt');
    });

    test('getSharedFiles returns list of manifests', () async {
      final files = await service.getSharedFiles();
      expect(files, hasLength(1));
      expect(files.first['fileId'], 'abc123');
    });

    test('unshareFile returns true', () async {
      final ok = await service.unshareFile('abc123');
      expect(ok, isTrue);
      expect(log.last.method, 'unshareFile');
      expect(log.last.arguments['fileId'], 'abc123');
    });

    test('getHostFiles returns remote file list', () async {
      final files = await service.getHostFiles('ghost');
      expect(files, hasLength(1));
      expect(files.first['name'], 'photo.jpg');
      expect(log.last.arguments['hostname'], 'ghost');
    });

    test('fetchHostFile returns started flag', () async {
      final started = await service.fetchHostFile('ghost', 'def456');
      expect(started, isTrue);
      expect(log.last.method, 'fetchHostFile');
      expect(log.last.arguments['fileId'], 'def456');
    });

    test('Phase 2 file methods return safe defaults on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(await service.shareLocalFile('/x'), isNull);
      expect(await service.getSharedFiles(), isEmpty);
      expect(await service.unshareFile('x'), isFalse);
      expect(await service.getHostFiles('h'), isEmpty);
      expect(await service.fetchHostFile('h', 'f'), isFalse);
    });

    // ---------------- Phase 3: collab ----------------

    test('createBoard returns room info', () async {
      final room = await service.createBoard('main-board');
      expect(room, isNotNull);
      expect(room!['roomId'], 'main-board');
      expect(log.last.method, 'createBoard');
    });

    test('sendStroke returns stroke id', () async {
      final id = await service.sendStroke('b', 0xFFFF0000, 3.0, [
        [1.0, 2.0],
        [3.0, 4.0],
      ]);
      expect(id, 'stroke-1');
      expect(log.last.arguments['roomId'], 'b');
      expect(log.last.arguments['points'], hasLength(2));
    });

    test('getBoard returns strokes', () async {
      final board = await service.getBoard('b');
      expect(board!['exists'], isTrue);
      final strokes = board['strokes'] as List;
      expect(strokes, hasLength(1));
      expect((strokes.first as Map)['strokeId'], 's1');
    });

    test('clearBoard returns cleared flag', () async {
      expect(await service.clearBoard('b'), isTrue);
    });

    test('createDoc and editDoc roundtrip', () async {
      final doc = await service.createDoc('notes', 'Team Notes');
      expect(doc!['docId'], 'notes');
      final rev = await service.editDoc('notes', 'salom');
      expect(rev, 1);
      expect(log.last.method, 'editDoc');
    });

    test('getDoc returns document', () async {
      final doc = await service.getDoc('notes');
      expect(doc!['rev'], 2);
      expect(doc['text'], 'salom');
    });

    test('createPoll returns poll map', () async {
      final poll = await service.createPoll('Savol?', ['ha', "yo'q"]);
      expect(poll!['pollId'], 'p1');
      expect(poll['options'], hasLength(2));
    });

    test('votePoll returns accepted flag', () async {
      expect(await service.votePoll('p1', 0), isTrue);
      expect(log.last.arguments['optionIndex'], 0);
    });

    test('getPolls returns tally data', () async {
      final polls = await service.getPolls();
      expect(polls, hasLength(1));
      expect(polls.first['totalVotes'], 2);
    });

    test('Phase 3 collab methods return safe defaults on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(await service.createBoard('b'), isNull);
      expect(await service.sendStroke('b', 1, 1, []), isNull);
      expect(await service.getBoard('b'), isNull);
      expect(await service.clearBoard('b'), isFalse);
      expect(await service.createDoc('d', 't'), isNull);
      expect(await service.editDoc('d', 'x'), isNull);
      expect(await service.getDoc('d'), isNull);
      expect(await service.createPoll('q', ['a', 'b']), isNull);
      expect(await service.votePoll('p', 0), isFalse);
      expect(await service.getPolls(), isEmpty);
    });

    // ---------------- Phase 4: app distribution ----------------

    test('getLocalApps returns shared APKs with package info', () async {
      final apps = await service.getLocalApps();
      expect(apps, hasLength(1));
      expect(apps.first['fileName'], 'meshdemo.apk');
      expect(apps.first['packageName'], 'com.techcorp.meshdemo');
      expect(apps.first['hasPackageInfo'], isTrue);
      expect(log.last.method, 'getLocalApps');
    });

    test('getHostApps returns remote APKs without package info', () async {
      final apps = await service.getHostApps('appserver');
      expect(apps, hasLength(1));
      expect(apps.first['fileName'], 'remote-app.apk');
      expect(apps.first['packageName'], isNull);
      expect(log.last.method, 'getHostApps');
      expect(log.last.arguments['hostname'], 'appserver');
    });

    test('installApk returns launched flag', () async {
      final ok = await service.installApk('app789');
      expect(ok, isTrue);
      expect(log.last.method, 'installApk');
      expect(log.last.arguments['fileId'], 'app789');
    });

    test('Phase 4 methods return safe defaults on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(await service.getLocalApps(), isEmpty);
      expect(await service.getHostApps('h'), isEmpty);
      expect(await service.installApk('x'), isFalse);
    });

    // ---------------- Phase 5: Internet Gateway ----------------

    test('startInternetGateway returns running map', () async {
      final res = await service.startInternetGateway(port: 8081);
      expect(res['running'], isTrue);
      expect(res['port'], 8081);
      expect(log.last.method, 'startInternetGateway');
      expect(log.last.arguments['port'], 8081);
    });

    test('startInternetGateway returns failed map on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final res = await service.startInternetGateway(port: 8081);
      expect(res['running'], isFalse);
      expect(res['error'], isNotNull);
    });

    test('stopInternetGateway returns true when stopped', () async {
      final ok = await service.stopInternetGateway();
      expect(ok, isTrue);
      expect(log.last.method, 'stopInternetGateway');
    });

    test('stopInternetGateway returns false on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final ok = await service.stopInternetGateway();
      expect(ok, isFalse);
    });

    test('getGateways returns list of gateway maps', () async {
      final gateways = await service.getGateways();
      expect(gateways, hasLength(1));
      expect(gateways.first['hostname'], 'gw1');
      expect(gateways.first['isSelf'], isTrue);
      expect(log.last.method, 'getGateways');
    });

    test('getGateways returns empty on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final gateways = await service.getGateways();
      expect(gateways, isEmpty);
    });

    test('testGateway returns probe result', () async {
      final res = await service.testGateway('gw1');
      expect(res, isNotNull);
      expect(res!['reachable'], isTrue);
      expect(res['latencyMs'], 42);
      expect(log.last.method, 'testGateway');
      expect(log.last.arguments['hostname'], 'gw1');
    });

    test('testGateway returns null on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final res = await service.testGateway('ghost');
      expect(res, isNull);
    });

    test('Phase 5 methods return safe defaults on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final startRes = await service.startInternetGateway(port: 0);
      expect(startRes['running'], isFalse);
      expect(await service.stopInternetGateway(), isFalse);
      expect(await service.getGateways(), isEmpty);
      expect(await service.testGateway('h'), isNull);
    });

    // ---------------- Phase 6: Emergency, RBAC, Search ----------------

    test('sendEmergencyAlert returns alert map', () async {
      final res = await service.sendEmergencyAlert(
        level: 2,
        title: 'Test Alert',
        message: 'This is a test',
      );
      expect(res, isNotNull);
      expect(res!['alertId'], isNotNull);
      expect(res['level'], 'WARNING');
      expect(log.last.method, 'sendEmergencyAlert');
    });

    test('acknowledgeEmergency returns true', () async {
      final ok = await service.acknowledgeEmergency('alert-123');
      expect(ok, isTrue);
      expect(log.last.method, 'acknowledgeEmergency');
    });

    test('cancelEmergency returns true', () async {
      final ok = await service.cancelEmergency('alert-123');
      expect(ok, isTrue);
      expect(log.last.method, 'cancelEmergency');
    });

    test('getEmergencies returns list', () async {
      final alerts = await service.getEmergencies();
      expect(alerts, hasLength(1));
      expect(alerts.first['title'], 'Fire');
      expect(log.last.method, 'getEmergencies');
    });

    test('setDeviceRole returns true', () async {
      final ok = await service.setDeviceRole('dev-1', 'ADMIN');
      expect(ok, isTrue);
      expect(log.last.method, 'setDeviceRole');
    });

    test('getDeviceRole returns role map', () async {
      final role = await service.getDeviceRole('dev-1');
      expect(role, isNotNull);
      expect(role!['role'], 'ADMIN');
    });

    test('setResourceRole returns true', () async {
      final ok = await service.setResourceRole(
        resourceType: 'board',
        resourceId: 'board-1',
        deviceId: 'dev-1',
        role: 'MODERATOR',
      );
      expect(ok, isTrue);
      expect(log.last.method, 'setResourceRole');
    });

    test('checkPermission returns true', () async {
      final has = await service.checkPermission(
        deviceId: 'dev-1',
        permission: 'board.draw',
        resourceType: 'board',
        resourceId: 'board-1',
      );
      expect(has, isTrue);
    });

    test('banDevice returns true', () async {
      final ok = await service.banDevice('dev-1');
      expect(ok, isTrue);
      expect(log.last.method, 'banDevice');
    });

    test('unbanDevice returns true', () async {
      final ok = await service.unbanDevice('dev-1');
      expect(ok, isTrue);
      expect(log.last.method, 'unbanDevice');
    });

    test('searchLocal returns results', () async {
      final results = await service.searchLocal(terms: ['meeting', 'budget'], resourceTypes: {'doc'}, maxResults: 10);
      expect(results, hasLength(1));
      expect(results.first['title'], 'Meeting Notes');
      expect(log.last.method, 'searchLocal');
    });

    test('searchDistributed returns queryId', () async {
      final queryId = await service.searchDistributed(terms: ['test'], resourceTypes: {}, maxResults: 20);
      expect(queryId, 'qry-123');
    });

    test('indexContent returns docId', () async {
      final docId = await service.indexContent(
        resourceType: 'doc',
        resourceId: 'doc-1',
        title: 'New Doc',
        content: 'Content here',
        tags: ['tag1'],
      );
      expect(docId, isNotNull);
      expect(docId!.startsWith('doc_'), isTrue);
      expect(log.last.method, 'indexContent');
    });

    test('removeFromIndex returns true', () async {
      final ok = await service.removeFromIndex('doc-1');
      expect(ok, isTrue);
      expect(log.last.method, 'removeFromIndex');
    });

    test('getSearchStats returns map', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        if (methodCall.method == 'getSearchStats') {
          return {'documents': 42, 'terms': 128, 'pendingQueries': 0};
        }
        return null;
      });

      final stats = await service.getSearchStats();
      expect(stats, isNotNull);
      expect(stats!['documents'], 42);
    });

    test('Phase 6 methods return safe defaults on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(await service.sendEmergencyAlert(level: 1, title: 't', message: 'm'), isNull);
      expect(await service.acknowledgeEmergency('x'), isFalse);
      expect(await service.cancelEmergency('x'), isFalse);
      expect(await service.getEmergencies(), isEmpty);
      expect(await service.setDeviceRole('d', 'r'), isFalse);
      expect(await service.getDeviceRole('d'), isNull);
      expect(await service.setResourceRole(resourceType: 'r', resourceId: 'r', deviceId: 'd', role: 'r'), isFalse);
      expect(await service.checkPermission(deviceId: 'd', permission: 'p'), isFalse);
      expect(await service.banDevice('d'), isFalse);
      expect(await service.unbanDevice('d'), isFalse);
      expect(await service.searchLocal(terms: ['t']), isEmpty);
      expect(await service.searchDistributed(terms: ['t']), isNull);
      expect(await service.indexContent(resourceType: 'r', resourceId: 'r', title: 't', content: 'c'), isNull);
      expect(await service.removeFromIndex('x'), isFalse);
      expect(await service.getSearchStats(), isNull);
    });

    test('getLocalNetInfo returns null on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      final info = await service.getLocalNetInfo();
      expect(info, isNull);
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

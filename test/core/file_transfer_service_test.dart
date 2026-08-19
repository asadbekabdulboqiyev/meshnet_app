import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshnet_app/core/file_transfer_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('FileTransferService', () {
    late FileTransferService service;
    const channel = MethodChannel('meshnet/file_transfer');
    late List<MethodCall> log;

    setUp(() {
      log = [];
      service = FileTransferService();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        log.add(methodCall);
        switch (methodCall.method) {
          case 'sendFile':
            return 'transfer-1';
          case 'sendImage':
            return 'transfer-img-1';
          case 'cancelTransfer':
            return true;
          case 'getTransferProgress':
            return {
              'transferId': 't1',
              'status': 'completed',
              'percent': 100,
              'bytesTransferred': 1024,
              'totalBytes': 1024,
            };
          default:
            return null;
        }
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('sendFile sends correct arguments', () async {
      final result = await service.sendFile('target-1', '/path/to/file.txt');
      expect(result, 'transfer-1');
      expect(log.last.method, 'sendFile');
      expect(log.last.arguments['targetDeviceId'], 'target-1');
      expect(log.last.arguments['filePath'], '/path/to/file.txt');
    });

    test('sendImage sends correct arguments', () async {
      final result = await service.sendImage('target-1', '/path/to/image.jpg');
      expect(result, 'transfer-img-1');
      expect(log.last.method, 'sendImage');
      expect(log.last.arguments['targetDeviceId'], 'target-1');
      expect(log.last.arguments['imagePath'], '/path/to/image.jpg');
    });

    test('cancelTransfer sends correct arguments', () async {
      final result = await service.cancelTransfer('transfer-1');
      expect(result, isTrue);
      expect(log.last.method, 'cancelTransfer');
      expect(log.last.arguments['transferId'], 'transfer-1');
    });

    test('cancelTransfer throws on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(
        () => service.cancelTransfer('t1'),
        throwsA(isA<PlatformException>()),
      );
    });

    test('getTransferProgress returns progress map', () async {
      final result = await service.getTransferProgress('t1');
      expect(result['transferId'], 't1');
      expect(result['status'], 'completed');
      expect(result['percent'], 100);
    });
  });
}

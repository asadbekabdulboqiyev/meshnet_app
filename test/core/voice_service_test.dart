import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshnet_app/core/voice_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('VoiceService', () {
    late VoiceService service;
    const channel = MethodChannel('meshnet/voice');
    late List<MethodCall> log;

    setUp(() {
      log = [];
      service = VoiceService();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        log.add(methodCall);
        switch (methodCall.method) {
          case 'startRecording':
            return true;
          case 'stopRecording':
            return {
              'filePath': '/tmp/voice.pcm',
              'durationMs': 5000,
              'fileSize': 160000,
            };
          case 'isRecording':
            return false;
          case 'sendVoiceMessage':
            return 'voice-msg-1';
          default:
            return null;
        }
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('startRecording returns true on success', () async {
      final result = await service.startRecording();
      expect(result, isTrue);
      expect(log.last.method, 'startRecording');
    });

    test('startRecording throws on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(
        () => service.startRecording(),
        throwsA(isA<PlatformException>()),
      );
    });

    test('stopRecording returns recording info', () async {
      final result = await service.stopRecording();
      expect(result['filePath'], '/tmp/voice.pcm');
      expect(result['durationMs'], 5000);
      expect(result['fileSize'], 160000);
      expect(log.last.method, 'stopRecording');
    });

    test('isRecording returns false when not recording', () async {
      final result = await service.isRecording();
      expect(result, isFalse);
    });

    test('isRecording returns true when recording', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        return true;
      });

      final result = await service.isRecording();
      expect(result, isTrue);
    });

    test('sendVoiceMessage returns message ID', () async {
      final result = await service.sendVoiceMessage(
        'target-1',
        '/tmp/voice.pcm',
        5000,
      );
      expect(result, 'voice-msg-1');
      expect(log.last.method, 'sendVoiceMessage');
      expect(log.last.arguments['targetDeviceId'], 'target-1');
      expect(log.last.arguments['filePath'], '/tmp/voice.pcm');
      expect(log.last.arguments['durationMs'], 5000);
    });

    test('sendVoiceMessage throws on error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
        throw PlatformException(code: 'error');
      });

      expect(
        () => service.sendVoiceMessage('t', '/tmp/f.pcm', 1000),
        throwsA(isA<PlatformException>()),
      );
    });
  });
}

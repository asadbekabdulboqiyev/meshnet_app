import 'package:flutter/services.dart';

class VoiceService {
  static const MethodChannel _method = MethodChannel('meshnet/voice');

  Future<bool> startRecording() async {
    final result = await _method.invokeMethod('startRecording');
    return result == true;
  }

  Future<Map<String, dynamic>> stopRecording() async {
    final result = await _method.invokeMethod('stopRecording');
    return Map<String, dynamic>.from(result);
  }

  Future<bool> isRecording() async {
    final result = await _method.invokeMethod('isRecording');
    return result == true;
  }

  Future<String> sendVoiceMessage(String targetDeviceId, String filePath, int durationMs) async {
    final result = await _method.invokeMethod('sendVoiceMessage', {
      'targetDeviceId': targetDeviceId,
      'filePath': filePath,
      'durationMs': durationMs,
    });
    return result as String;
  }
}

import 'package:flutter/services.dart';

class FileTransferService {
  static const MethodChannel _method = MethodChannel('meshnet/file_transfer');

  Future<String> sendFile(String targetDeviceId, String filePath) async {
    final result = await _method.invokeMethod('sendFile', {
      'targetDeviceId': targetDeviceId,
      'filePath': filePath,
    });
    return result as String;
  }

  Future<String> sendImage(String targetDeviceId, String imagePath) async {
    final result = await _method.invokeMethod('sendImage', {
      'targetDeviceId': targetDeviceId,
      'imagePath': imagePath,
    });
    return result as String;
  }

  Future<bool> cancelTransfer(String transferId) async {
    final result = await _method.invokeMethod('cancelTransfer', {
      'transferId': transferId,
    });
    return result == true;
  }

  Future<Map<String, dynamic>> getTransferProgress(String transferId) async {
    final result = await _method.invokeMethod('getTransferProgress', {
      'transferId': transferId,
    });
    return Map<String, dynamic>.from(result);
  }
}

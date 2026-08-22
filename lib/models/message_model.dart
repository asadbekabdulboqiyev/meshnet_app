enum MessageType { text, image, file, voice, groupText }

enum MessageStatus { sending, pending, sent, delivered, read, failed, transferring }

class ChatMessage {
  final String messageId;
  final String text;
  final MessageType type;
  final bool fromMe;
  final String? fromDeviceId;
  final MessageStatus status;
  final DateTime? timestamp;
  final String? localPath;
  final String? remotePath;
  final String? fileName;
  final int? fileSize;
  final String? mimeType;
  final double? transferProgress;
  final Duration? audioDuration;
  final String? groupName;
  final String? senderName;
  
  // Voice message playback
  final bool isPlaying;
  final double playbackSpeed;
  final double playbackPosition;
  
  // Reactions
  final Map<String, int> reactions; // emoji -> count

  const ChatMessage({
    required this.messageId,
    this.text = '',
    this.type = MessageType.text,
    required this.fromMe,
    this.fromDeviceId,
    this.status = MessageStatus.sent,
    this.timestamp,
    this.localPath,
    this.remotePath,
    this.fileName,
    this.fileSize,
    this.mimeType,
    this.transferProgress,
    this.audioDuration,
    this.groupName,
    this.senderName,
    this.isPlaying = false,
    this.playbackSpeed = 1.0,
    this.playbackPosition = 0.0,
    this.reactions = const {},
  });

  ChatMessage copyWith({
    String? messageId,
    String? text,
    MessageType? type,
    MessageStatus? status,
    DateTime? timestamp,
    String? localPath,
    String? remotePath,
    double? transferProgress,
    Duration? audioDuration,
    bool? isPlaying,
    double? playbackSpeed,
    double? playbackPosition,
    Map<String, int>? reactions,
  }) {
    return ChatMessage(
      messageId: messageId ?? this.messageId,
      text: text ?? this.text,
      type: type ?? this.type,
      fromMe: fromMe,
      fromDeviceId: fromDeviceId,
      status: status ?? this.status,
      timestamp: timestamp ?? this.timestamp,
      localPath: localPath ?? this.localPath,
      remotePath: remotePath ?? this.remotePath,
      fileName: fileName,
      fileSize: fileSize,
      mimeType: mimeType,
      transferProgress: transferProgress ?? this.transferProgress,
      audioDuration: audioDuration ?? this.audioDuration,
      groupName: groupName,
      senderName: senderName,
      isPlaying: isPlaying ?? this.isPlaying,
      playbackSpeed: playbackSpeed ?? this.playbackSpeed,
      playbackPosition: playbackPosition ?? this.playbackPosition,
      reactions: reactions ?? this.reactions,
    );
  }

  String get displaySize {
    if (fileSize == null) return '';
    if (fileSize! < 1024) return '$fileSize B';
    if (fileSize! < 1024 * 1024) return '${(fileSize! / 1024).toStringAsFixed(1)} KB';
    return '${(fileSize! / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  String get displayDuration {
    if (audioDuration == null) return '0:00';
    final minutes = audioDuration!.inMinutes;
    final seconds = audioDuration!.inSeconds % 60;
    return '$minutes:${seconds.toString().padLeft(2, '0')}';
  }
  
  // Voice message helpers
  bool get isVoiceMessage => type == MessageType.voice;
  
  String get formattedPosition {
    final seconds = playbackPosition.toInt();
    final minutes = seconds ~/ 60;
    final remainingSeconds = seconds % 60;
    return '$minutes:${remainingSeconds.toString().padLeft(2, '0')}';
  }
}

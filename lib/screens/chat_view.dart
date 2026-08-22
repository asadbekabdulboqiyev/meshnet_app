import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/mesh_service.dart';
import '../models/message_model.dart';
import '../theme/app_theme.dart';
import '../widgets/voice_message_player.dart';

/// Chat screen — no gradient.
class ChatView extends ConsumerStatefulWidget {
  const ChatView({
    super.key,
    required this.peerId,
    required this.peerName,
  });

  final String peerId;
  final String peerName;

  @override
  ConsumerState<ChatView> createState() => _ChatViewState();
}

class _ChatViewState extends ConsumerState<ChatView> {
  final List<ChatMessage> _messages = [];
  final TextEditingController _controller = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  StreamSubscription<Map<String, dynamic>>? _sub;
  bool _isRecording = false;

  @override
  void initState() {
    super.initState();
    final service = ref.read(meshServiceProvider);
    // Mark messages as read when chat is opened
    service.markAsRead(widget.peerId);
    _sub = service.events.listen((event) {
      if (!mounted) return;
      final evt = event['event'];
      if (evt == 'messageReceived') {
        final from = event['fromDeviceId'] as String? ?? '';
        if (from == widget.peerId) {
          final ts = event['timestamp'] as int?;
          setState(() {
            _messages.add(ChatMessage(
              messageId: 'msg:${DateTime.now().millisecondsSinceEpoch}',
              text: event['message'] as String? ?? '',
              fromMe: false,
              timestamp: ts != null ? DateTime.fromMillisecondsSinceEpoch(ts) : DateTime.now(),
            ));
          });
          _scrollToBottom();
        }
      } else if (evt == 'voiceMessageReceived') {
        final from = event['fromDeviceId'] as String? ?? '';
        if (from == widget.peerId) {
          final ts = event['timestamp'] as int?;
          final durationMs = event['durationMs'] as int? ?? 0;
          setState(() {
            _messages.add(ChatMessage(
              messageId: event['messageId'] as String? ??
                  'voice:${DateTime.now().millisecondsSinceEpoch}',
              type: MessageType.voice,
              fromMe: false,
              fromDeviceId: from,
              localPath: event['filePath'] as String?,
              audioDuration: Duration(milliseconds: durationMs),
              timestamp: ts != null ? DateTime.fromMillisecondsSinceEpoch(ts) : DateTime.now(),
            ));
          });
          _scrollToBottom();
        }
      } else if (evt == 'voicePlayback') {
        final messageId = event['messageId'] as String? ?? '';
        final idx = _messages.indexWhere((m) => m.messageId == messageId);
        if (idx != -1) {
          final positionMs = event['positionMs'] as int? ?? 0;
          final isPlaying = event['isPlaying'] as bool? ?? false;
          final finished = event['finished'] as bool? ?? false;
          setState(() {
            _messages[idx] = _messages[idx].copyWith(
              isPlaying: isPlaying && !finished,
              playbackPosition: finished ? 0.0 : positionMs / 1000.0,
            );
          });
        }
      } else if (evt == 'deliveryStatus' || evt == 'outboxStatus') {
        final messageId = event['messageId'] as String? ?? '';
        final status = event['status'] as String? ?? '';
        final newStatus = switch (status) {
          'delivered' => MessageStatus.delivered,
          'failed' => MessageStatus.failed,
          'expired' => MessageStatus.failed,
          _ => MessageStatus.pending,
        };
        setState(() {
          final idx = _messages.indexWhere(
            (m) => m.fromMe && m.messageId == messageId,
          );
          if (idx != -1) {
            _messages[idx] = _messages[idx].copyWith(status: newStatus);
          }
        });
      } else if (evt == 'peerFound') {
        final deviceId = event['deviceId'] as String? ?? '';
        if (deviceId == widget.peerId) {
          final failed = _messages
              .where((m) => m.fromMe && m.status == MessageStatus.failed)
              .toList();
          for (final msg in failed) {
            _retry(msg);
          }
        }
      } else if (evt == 'readReceipt') {
        final fromDeviceId = event['fromDeviceId'] as String? ?? '';
        if (fromDeviceId == widget.peerId) {
          final messageIds = (event['messageIds'] as List<dynamic>?)
              ?.map((e) => e.toString())
              .toList() ?? [];
          setState(() {
            for (final msgId in messageIds) {
              final idx = _messages.indexWhere(
                (m) => m.fromMe && m.messageId == msgId,
              );
              if (idx != -1) {
                _messages[idx] = _messages[idx].copyWith(status: MessageStatus.read);
              }
            }
          });
        }
      }
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _controller.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          0,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _send() async {
    final text = _controller.text.trim();
    if (text.isEmpty) return;
    _controller.clear();
    final result =
        await ref.read(meshServiceProvider).sendMessage(widget.peerId, text);
    final messageId = result?['messageId'] as String? ?? '';
    final ok = result?['status'] == 'sent';
    final ts = result?['timestamp'] as int?;
    final msg = ChatMessage(
      text: text,
      fromMe: true,
      messageId: messageId,
      status: ok ? MessageStatus.pending : MessageStatus.failed,
      timestamp: ts != null ? DateTime.fromMillisecondsSinceEpoch(ts) : DateTime.now(),
    );
    setState(() => _messages.add(msg));
    _scrollToBottom();
  }

  Future<void> _retry(ChatMessage msg, {bool find = true}) async {
    final service = ref.read(meshServiceProvider);
    if (find) await service.findPeer(widget.peerId);
    final result = await service.sendMessage(widget.peerId, msg.text);
    final messageId = result?['messageId'] as String? ?? '';
    final ok = result?['status'] == 'sent';
    if (!mounted) return;
    setState(() {
      final idx = _messages.indexOf(msg);
      if (idx != -1) {
        _messages[idx] = msg.copyWith(
          messageId: messageId,
          status: ok ? MessageStatus.pending : MessageStatus.failed,
        );
      }
    });
  }

  /// Hold-to-record: mic bosilganda yozishni boshlaydi.
  Future<void> _startVoiceRecording() async {
    final service = ref.read(meshServiceProvider);
    await service.startRecording();
    if (mounted) setState(() => _isRecording = true);
  }

  /// Release: yozishni to'xtatadi va yuboradi.
  Future<void> _stopVoiceRecording({bool cancel = false}) async {
    if (!_isRecording) return;
    setState(() => _isRecording = false);
    final service = ref.read(meshServiceProvider);
    final result = await service.stopRecording();
    if (cancel) return;
    final filePath = result['filePath'] as String?;
    if (filePath == null || filePath.isEmpty) return;
    final durationMs = result['durationMs'] as int? ?? 0;
    final messageId =
        await service.sendVoiceMessage(widget.peerId, filePath, durationMs);
    if (!mounted) return;
    setState(() {
      _messages.add(ChatMessage(
        messageId: messageId ?? 'voice:${DateTime.now().millisecondsSinceEpoch}',
        type: MessageType.voice,
        fromMe: true,
        localPath: filePath,
        audioDuration: Duration(milliseconds: durationMs),
        status: MessageStatus.pending,
        timestamp: DateTime.now(),
      ));
    });
    _scrollToBottom();
  }

  /// Playback tezligini 0.5x → 1x → 1.5x → 2x → 0.5x almashtiradi.
  Future<void> _cycleVoiceSpeed(ChatMessage msg) async {
    const speeds = [0.5, 1.0, 1.5, 2.0];
    final idx = _messages.indexWhere((m) => m.messageId == msg.messageId);
    if (idx == -1) return;
    final current = _messages[idx].playbackSpeed;
    final next = speeds[(speeds.indexOf(current) + 1) % speeds.length];
    await ref
        .read(meshServiceProvider)
        .setVoicePlaybackSpeed(msg.messageId, next);
    if (!mounted) return;
    setState(() {
      _messages[idx] = _messages[idx].copyWith(playbackSpeed: next);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: MeshAppTheme.bgDeep,
      appBar: AppBar(
        backgroundColor: MeshAppTheme.bgSurface,
        titleSpacing: 0,
        title: Row(
          children: [
            CircleAvatar(
              radius: 18,
              backgroundColor: MeshAppTheme.primary.withValues(alpha: 0.12),
              child: Text(
                widget.peerName.isNotEmpty
                    ? widget.peerName[0].toUpperCase()
                    : '?',
                style: const TextStyle(
                  color: MeshAppTheme.primary,
                  fontWeight: FontWeight.w800,
                  fontSize: 15,
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.peerName,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                      color: MeshAppTheme.textWhite,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  Row(
                    children: [
                      Container(
                        width: 6,
                        height: 6,
                        decoration: const BoxDecoration(
                          color: MeshAppTheme.success,
                          shape: BoxShape.circle,
                        ),
                      ),
                      const SizedBox(width: 5),
                      const Text(
                        'Encrypted',
                        style: TextStyle(
                          fontSize: 12,
                          color: MeshAppTheme.success,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      body: Column(
        children: [
          Expanded(
            child: _messages.isEmpty
                ? _EmptyChat()
                : ListView.builder(
                    controller: _scrollController,
                    reverse: true,
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    itemCount: _messages.length,
                    itemBuilder: (context, i) {
                      final msg = _messages[_messages.length - 1 - i];
                      return _MessageBubble(
                        message: msg,
                        onRetry: msg.fromMe && msg.status == MessageStatus.failed
                            ? () => _retry(msg)
                            : null,
                        onSpeedCycle: msg.isVoiceMessage
                            ? () => _cycleVoiceSpeed(msg)
                            : null,
                      );
                    },
                  ),
          ),
          _ChatInput(
            controller: _controller,
            onSend: _send,
            isRecording: _isRecording,
            onRecordStart: _startVoiceRecording,
            onRecordEnd: () => _stopVoiceRecording(),
            onRecordCancel: () => _stopVoiceRecording(cancel: true),
          ),
        ],
      ),
    );
  }
}

class _EmptyChat extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 64,
            height: 64,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: MeshAppTheme.primary.withValues(alpha: 0.08),
            ),
            child: Icon(
              Icons.lock_outline_rounded,
              size: 28,
              color: MeshAppTheme.primary.withValues(alpha: 0.5),
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'End-to-End encrypted',
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: MeshAppTheme.textGray,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            'Send your message',
            style: TextStyle(fontSize: 13, color: MeshAppTheme.textDim),
          ),
        ],
      ),
    );
  }
}

class _ChatInput extends StatelessWidget {
  const _ChatInput({
    required this.controller,
    required this.onSend,
    this.isRecording = false,
    this.onRecordStart,
    this.onRecordEnd,
    this.onRecordCancel,
  });

  final TextEditingController controller;
  final VoidCallback onSend;
  final bool isRecording;
  final VoidCallback? onRecordStart;
  final VoidCallback? onRecordEnd;
  final VoidCallback? onRecordCancel;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: const BoxDecoration(
        color: MeshAppTheme.bgSurface,
        border: Border(
          top: BorderSide(color: MeshAppTheme.border, width: 1),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            // Hold-to-record voice button
            GestureDetector(
              onLongPressStart: (_) => onRecordStart?.call(),
              onLongPressEnd: (_) => onRecordEnd?.call(),
              onLongPressCancel: onRecordCancel,
              child: Container(
                width: 42,
                height: 42,
                margin: const EdgeInsets.only(right: 8),
                decoration: BoxDecoration(
                  color: isRecording ? MeshAppTheme.error : MeshAppTheme.bgCard,
                  shape: BoxShape.circle,
                  border: Border.all(color: MeshAppTheme.border, width: 1),
                ),
                child: Icon(
                  isRecording ? Icons.stop_rounded : Icons.mic_none_rounded,
                  color: isRecording ? Colors.white : MeshAppTheme.textGray,
                  size: 20,
                ),
              ),
            ),
            Expanded(
              child: isRecording
                  ? Container(
                      height: 42,
                      alignment: Alignment.centerLeft,
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      decoration: BoxDecoration(
                        color: MeshAppTheme.error.withValues(alpha: 0.08),
                        borderRadius: BorderRadius.circular(21),
                        border: Border.all(
                          color: MeshAppTheme.error.withValues(alpha: 0.3),
                          width: 1,
                        ),
                      ),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.graphic_eq_rounded,
                            color: MeshAppTheme.error,
                            size: 18,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'Recording... release to send',
                            style: TextStyle(
                              color: MeshAppTheme.error.withValues(alpha: 0.9),
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    )
                  : TextField(
                      controller: controller,
                      maxLines: 4,
                      minLines: 1,
                      textCapitalization: TextCapitalization.sentences,
                      style: const TextStyle(
                          color: MeshAppTheme.textWhite, fontSize: 15),
                      decoration: InputDecoration(
                        hintText: 'Type a message...',
                        hintStyle: TextStyle(color: MeshAppTheme.textDim),
                        contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16, vertical: 10),
                      ),
                      onSubmitted: (_) => onSend(),
                    ),
            ),
            const SizedBox(width: 8),
            GestureDetector(
              onTap: onSend,
              child: Container(
                width: 42,
                height: 42,
                decoration: const BoxDecoration(
                  color: MeshAppTheme.primary,
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.send_rounded, color: Colors.white, size: 20),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({
    required this.message,
    this.onRetry,
    this.onSpeedCycle,
  });

  final ChatMessage message;
  final VoidCallback? onRetry;
  final VoidCallback? onSpeedCycle;

  String _formatTime(DateTime dt) {
    final h = dt.hour.toString().padLeft(2, '0');
    final m = dt.minute.toString().padLeft(2, '0');
    return '$h:$m';
  }

  @override
  Widget build(BuildContext context) {
    final me = message.fromMe;
    
    // Voice messages use the specialized player
    if (message.isVoiceMessage) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          mainAxisAlignment: me ? MainAxisAlignment.end : MainAxisAlignment.start,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            if (!me) const SizedBox(width: 4),
            VoiceMessagePlayer(
              message: message,
              onPlayPause: () {},
              onSpeedChange: onSpeedCycle,
            ),
            if (me) const SizedBox(width: 4),
          ],
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: me ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!me) const SizedBox(width: 4),
          Flexible(
            child: GestureDetector(
              onTap: onRetry,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                constraints: BoxConstraints(
                  maxWidth: MediaQuery.of(context).size.width * 0.75,
                ),
                decoration: BoxDecoration(
                  color: me ? MeshAppTheme.sentBubble : MeshAppTheme.receivedBubble,
                  borderRadius: BorderRadius.only(
                    topLeft: const Radius.circular(18),
                    topRight: const Radius.circular(18),
                    bottomLeft: Radius.circular(me ? 18 : 4),
                    bottomRight: Radius.circular(me ? 4 : 18),
                  ),
                  border: me
                      ? null
                      : Border.all(color: MeshAppTheme.border, width: 1),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      message.text,
                      style: const TextStyle(
                        fontSize: 15,
                        color: Colors.white,
                        height: 1.4,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (message.timestamp != null)
                          Text(
                            _formatTime(message.timestamp!),
                            style: TextStyle(
                              fontSize: 10,
                              color: me
                                  ? Colors.white.withValues(alpha: 0.5)
                                  : MeshAppTheme.textDim,
                            ),
                          ),
                        if (me) ...[
                          const SizedBox(width: 4),
                          _StatusIcon(status: message.status),
                        ],
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (me) const SizedBox(width: 4),
        ],
      ),
    );
  }
}

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.status});
  final MessageStatus status;

  @override
  Widget build(BuildContext context) {
    return switch (status) {
      MessageStatus.sending => Icon(Icons.access_time_rounded, size: 13, color: Colors.white.withValues(alpha: 0.4)),
      MessageStatus.pending => Icon(Icons.access_time_rounded, size: 13, color: Colors.white.withValues(alpha: 0.4)),
      MessageStatus.sent => Icon(Icons.done_rounded, size: 13, color: Colors.white.withValues(alpha: 0.6)),
      MessageStatus.delivered => Icon(Icons.done_all_rounded, size: 13, color: MeshAppTheme.success),
      MessageStatus.read => Icon(Icons.done_all_rounded, size: 13, color: MeshAppTheme.info),
      MessageStatus.failed => Icon(Icons.error_outline_rounded, size: 13, color: MeshAppTheme.error),
      MessageStatus.transferring => Icon(Icons.sync_rounded, size: 13, color: Colors.white.withValues(alpha: 0.6)),
    };
  }
}

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/mesh_service.dart';
import '../models/message_model.dart';
import '../models/group_model.dart';
import '../theme/app_theme.dart';

class GroupChatView extends ConsumerStatefulWidget {
  final MeshGroup group;

  const GroupChatView({super.key, required this.group});

  @override
  ConsumerState<GroupChatView> createState() => _GroupChatViewState();
}

class _GroupChatViewState extends ConsumerState<GroupChatView> {
  final TextEditingController _controller = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final List<ChatMessage> _messages = [];
  StreamSubscription? _eventSub;
  bool _isLoadingHistory = true;

  @override
  void initState() {
    super.initState();
    _loadHistory();
    _listenEvents();
  }

  Future<void> _loadHistory() async {
    setState(() => _isLoadingHistory = true);
    try {
      final history = await ref.read(meshServiceProvider).getGroupMessages(widget.group.groupId);
      if (!mounted) return;
      final parsed = history.map((m) {
        final ts = m['timestamp'] as int?;
        return ChatMessage(
          messageId: m['messageId'] as String? ?? '',
          text: m['message'] as String? ?? '',
          type: MessageType.groupText,
          fromMe: m['fromMe'] == true,
          fromDeviceId: m['senderId'] as String?,
          senderName: m['senderName'] as String?,
          status: MessageStatus.delivered,
          timestamp: ts != null ? DateTime.fromMillisecondsSinceEpoch(ts) : DateTime.now(),
        );
      }).toList();
      setState(() {
        _messages.addAll(parsed.reversed);
        _isLoadingHistory = false;
      });
    } catch (e) {
      if (mounted) setState(() => _isLoadingHistory = false);
    }
  }

  void _listenEvents() {
    _eventSub = ref.read(meshServiceProvider).events.listen((event) {
      if (!mounted) return;
      final evt = event['event'];

      if (evt == 'groupMessageReceived' &&
          event['groupId'] == widget.group.groupId) {
        final ts = event['timestamp'] as int?;
        setState(() {
          _messages.insert(0, ChatMessage(
            messageId: event['messageId'] ?? '',
            text: event['message'] ?? '',
            type: MessageType.groupText,
            fromMe: false,
            fromDeviceId: event['senderId'],
            senderName: event['senderName'] ?? 'Unknown',
            timestamp: ts != null ? DateTime.fromMillisecondsSinceEpoch(ts) : DateTime.now(),
          ));
        });
      } else if (evt == 'groupDeliveryStatus' &&
          event['groupId'] == widget.group.groupId) {
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
        final failed = _messages
            .where((m) => m.fromMe && m.status == MessageStatus.failed)
            .toList();
        for (final msg in failed) {
          _retry(msg, find: false);
        }
      }
    });
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    _controller.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _send() async {
    final text = _controller.text.trim();
    if (text.isEmpty) return;
    _controller.clear();

    setState(() {
      _messages.insert(0, ChatMessage(
        messageId: 'self:${DateTime.now().millisecondsSinceEpoch}',
        text: text,
        type: MessageType.groupText,
        fromMe: true,
        status: MessageStatus.sending,
        timestamp: DateTime.now(),
      ));
    });

    try {
      await ref.read(meshServiceProvider).sendGroupMessage(widget.group.groupId, text);
      if (mounted) {
        setState(() {
          _messages.first = _messages.first.copyWith(status: MessageStatus.sent);
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _messages.first = _messages.first.copyWith(status: MessageStatus.failed);
        });
      }
    }
  }

  void _retry(ChatMessage msg, {bool find = true}) async {
    try {
      await ref.read(meshServiceProvider).sendGroupMessage(widget.group.groupId, msg.text);
      if (mounted) {
        setState(() {
          final idx = _messages.indexOf(msg);
          if (idx != -1) {
            _messages[idx] = msg.copyWith(status: MessageStatus.pending);
          }
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          final idx = _messages.indexOf(msg);
          if (idx != -1) {
            _messages[idx] = msg.copyWith(status: MessageStatus.failed);
          }
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.group.name),
            Text(
              '${widget.group.members.length} members',
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.normal),
            ),
          ],
        ),
      ),
      body: Column(
        children: [
          Expanded(
            child: _isLoadingHistory
                ? const Center(child: CircularProgressIndicator())
                : _messages.isEmpty
                    ? Center(
                        child: Text(
                          'No messages yet',
                          style: TextStyle(color: MeshAppTheme.textDim),
                        ),
                      )
                    : ListView.builder(
                        controller: _scrollController,
                        reverse: true,
                        padding: const EdgeInsets.all(16),
                        itemCount: _messages.length,
                        itemBuilder: (context, index) {
                          final msg = _messages[index];
                          return _buildBubble(msg);
                        },
                      ),
          ),
          _buildInput(),
        ],
      ),
    );
  }

  Widget _buildBubble(ChatMessage msg) {
    return Align(
      alignment: msg.fromMe ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        constraints: const BoxConstraints(maxWidth: 280),
        child: Column(
          crossAxisAlignment:
              msg.fromMe ? CrossAxisAlignment.end : CrossAxisAlignment.start,
          children: [
            if (!msg.fromMe && msg.senderName != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text(
                  msg.senderName!,
                  style: TextStyle(
                    color: MeshAppTheme.info,
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            GestureDetector(
              onTap: msg.fromMe && msg.status == MessageStatus.failed
                  ? () => _retry(msg)
                  : null,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                decoration: BoxDecoration(
                  color: msg.fromMe
                      ? MeshAppTheme.primary
                      : MeshAppTheme.bgElevated,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(
                      msg.text,
                      style: const TextStyle(color: Colors.white, fontSize: 14),
                    ),
                    if (msg.fromMe) ...[
                      const SizedBox(height: 4),
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (msg.timestamp != null)
                            Text(
                              _formatTime(msg.timestamp!),
                              style: TextStyle(
                                fontSize: 10,
                                color: Colors.white.withValues(alpha: 0.5),
                              ),
                            ),
                          const SizedBox(width: 4),
                          _StatusIcon(status: msg.status),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatTime(DateTime dt) {
    final h = dt.hour.toString().padLeft(2, '0');
    final m = dt.minute.toString().padLeft(2, '0');
    return '$h:$m';
  }

  Widget _buildInput() {
    return Container(
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: MeshAppTheme.bgDeep,
        border: Border(top: BorderSide(color: MeshAppTheme.border)),
      ),
      child: SafeArea(
        child: Row(
          children: [
            Expanded(
              child: TextField(
                controller: _controller,
                style: const TextStyle(color: Colors.white),
                decoration: InputDecoration(
                  hintText: 'Type a message...',
                  hintStyle: TextStyle(color: MeshAppTheme.textDim),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(24),
                    borderSide: BorderSide.none,
                  ),
                  filled: true,
                  fillColor: MeshAppTheme.bgElevated,
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 10,
                  ),
                ),
                onSubmitted: (_) => _send(),
              ),
            ),
            const SizedBox(width: 8),
            IconButton(
              icon: Icon(Icons.send, color: MeshAppTheme.primary),
              onPressed: _send,
            ),
          ],
        ),
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

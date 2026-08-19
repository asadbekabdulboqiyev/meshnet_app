import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/providers.dart';
import '../core/group_service.dart';
import '../models/message_model.dart';
import '../models/group_model.dart';

class GroupChatView extends ConsumerStatefulWidget {
  final MeshGroup group;

  const GroupChatView({super.key, required this.group});

  @override
  ConsumerState<GroupChatView> createState() => _GroupChatViewState();
}

class _GroupChatViewState extends ConsumerState<GroupChatView> {
  final TextEditingController _controller = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final GroupService _groupService = GroupService();
  final List<ChatMessage> _messages = [];
  StreamSubscription? _eventSub;

  @override
  void initState() {
    super.initState();
    _listenEvents();
  }

  void _listenEvents() {
    _eventSub = ref.read(meshServiceProvider).events.listen((event) {
      if (event['event'] == 'groupMessageReceived' &&
          event['groupId'] == widget.group.groupId) {
        setState(() {
          _messages.insert(0, ChatMessage(
            messageId: event['messageId'] ?? '',
            text: event['message'] ?? '',
            type: MessageType.groupText,
            fromMe: false,
            fromDeviceId: event['senderId'],
            senderName: event['senderName'] ?? 'Unknown',
            timestamp: DateTime.now(),
          ));
        });
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
      await _groupService.sendGroupMessage(widget.group.groupId, text);
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
        actions: [
          IconButton(
            icon: const Icon(Icons.info_outline),
            onPressed: () {},
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: _messages.isEmpty
                ? const Center(
                    child: Text(
                      'No messages yet',
                      style: TextStyle(color: Colors.white54),
                    ),
                  )
                : ListView.builder(
                    controller: _scrollController,
                    reverse: true,
                    padding: const EdgeInsets.all(16),
                    itemCount: _messages.length,
                    itemBuilder: (context, index) {
                      return _buildBubble(_messages[index]);
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
                  style: const TextStyle(
                    color: Color(0xFF80CBC4),
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: msg.fromMe
                    ? const Color(0xFF00897B)
                    : const Color(0xFF2A2A2A),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Text(
                msg.text,
                style: const TextStyle(color: Colors.white, fontSize: 14),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildInput() {
    return Container(
      padding: const EdgeInsets.all(8),
      decoration: const BoxDecoration(
        color: Color(0xFF1A1A1A),
        border: Border(top: BorderSide(color: Colors.white12)),
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
                  hintStyle: TextStyle(color: Colors.white.withOpacity(0.4)),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(24),
                    borderSide: BorderSide.none,
                  ),
                  filled: true,
                  fillColor: const Color(0xFF2A2A2A),
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
              icon: const Icon(Icons.send, color: Color(0xFF00897B)),
              onPressed: _send,
            ),
          ],
        ),
      ),
    );
  }
}

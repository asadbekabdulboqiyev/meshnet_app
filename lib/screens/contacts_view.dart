import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/providers.dart';
import '../core/mesh_service.dart';
import '../theme/app_theme.dart';
import '../models/group_model.dart';
import 'chat_view.dart';
import 'group_chat_view.dart';
import 'create_group_screen.dart';

/// Chats list — groups + 1-on-1 peers.
class ContactsView extends ConsumerStatefulWidget {
  const ContactsView({super.key});

  @override
  ConsumerState<ContactsView> createState() => _ContactsViewState();
}

class _ContactsViewState extends ConsumerState<ContactsView> {
  Map<String, int> _unreadCounts = {};

  @override
  void initState() {
    super.initState();
    _loadUnreadCounts();
    ref.read(meshServiceProvider).events.listen((event) {
      if (!mounted) return;
      if (event['event'] == 'messageReceived' ||
          event['event'] == 'readReceipt' ||
          event['event'] == 'groupMessageReceived') {
        _loadUnreadCounts();
      }
    });
  }

  Future<void> _loadUnreadCounts() async {
    final counts = await ref.read(meshServiceProvider).getUnreadCounts();
    if (!mounted) return;
    setState(() {
      _unreadCounts = Map<String, int>.from(counts['byDevice'] ?? {});
    });
  }

  Future<void> _openCreateGroup() async {
    final result = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => const CreateGroupScreen()),
    );
    if (result == true) {
      ref.invalidate(groupsProvider);
    }
  }

  @override
  Widget build(BuildContext context) {
    final peers = ref.watch(peersProvider).value ?? [];
    final groupsAsync = ref.watch(groupsProvider);

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            const Text('Chats'),
            const SizedBox(width: 10),
            if (peers.isNotEmpty)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: MeshAppTheme.primary,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  '${peers.length}',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _openCreateGroup,
        backgroundColor: MeshAppTheme.primary,
        child: const Icon(Icons.group_add_rounded, color: Colors.white),
      ),
      body: _buildBody(peers, groupsAsync),
    );
  }

  Widget _buildBody(List<Map<String, dynamic>> peers, AsyncValue<List<Map<String, dynamic>>> groupsAsync) {
    final groups = groupsAsync.value ?? [];
    final hasGroups = groups.isNotEmpty;
    final hasPeers = peers.isNotEmpty;

    if (!hasGroups && !hasPeers) return _EmptyState();

    return ListView(
      children: [
        // ── Groups section ──
        if (hasGroups) ...[
          _SectionHeader(title: 'Groups', count: groups.length),
          ...groups.map((g) => _GroupTile(
            group: g,
            unreadCount: _unreadCounts[g['groupId'] as String? ?? ''] ?? 0,
            onTap: () {
              final meshGroup = MeshGroup.fromMap(g);
              Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => GroupChatView(group: meshGroup),
                ),
              );
            },
          )),
        ],

        // ── Contacts section ──
        if (hasPeers) ...[
          _SectionHeader(title: 'Contacts', count: peers.length),
          ...List.generate(peers.length, (i) {
            final peer = peers[i];
            return _ContactTile(
              peer: peer,
              unreadCount: _unreadCounts[peer['deviceId'] as String? ?? ''] ?? 0,
            );
          }),
        ],
      ],
    );
  }
}

// ── Section header ──

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title, required this.count});
  final String title;
  final int count;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Row(
        children: [
          Text(
            title,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: MeshAppTheme.textDim,
              letterSpacing: 0.5,
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
            decoration: BoxDecoration(
              color: MeshAppTheme.border,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              '$count',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: MeshAppTheme.textDim,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Group tile ──

class _GroupTile extends StatelessWidget {
  const _GroupTile({required this.group, this.onTap, this.unreadCount = 0});
  final Map<String, dynamic> group;
  final VoidCallback? onTap;
  final int unreadCount;

  @override
  Widget build(BuildContext context) {
    final name = group['name'] as String? ?? 'Group';
    final members = (group['members'] as List?) ?? [];
    final memberCount = members.length;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              Stack(
                children: [
                  CircleAvatar(
                    radius: 24,
                    backgroundColor: MeshAppTheme.info.withValues(alpha: 0.15),
                    child: Icon(
                      Icons.group_rounded,
                      color: MeshAppTheme.info,
                      size: 22,
                    ),
                  ),
                  if (unreadCount > 0)
                    Positioned(
                      right: -2,
                      top: -2,
                      child: Container(
                        constraints: const BoxConstraints(minWidth: 20, minHeight: 20),
                        padding: const EdgeInsets.symmetric(horizontal: 5),
                        decoration: BoxDecoration(
                          color: MeshAppTheme.error,
                          shape: BoxShape.circle,
                          border: Border.all(color: MeshAppTheme.bgDeep, width: 2),
                        ),
                        child: Center(
                          child: Text(
                            unreadCount > 99 ? '99+' : '$unreadCount',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 10,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: MeshAppTheme.textWhite,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 3),
                    Text(
                      '$memberCount ${memberCount == 1 ? 'member' : 'members'}',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        color: MeshAppTheme.textDim,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(Icons.chevron_right_rounded, color: MeshAppTheme.textDim, size: 22),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Empty state ──

class _EmptyState extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 80,
            height: 80,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: MeshAppTheme.primary.withValues(alpha: 0.08),
            ),
            child: Icon(
              Icons.forum_rounded,
              size: 36,
              color: MeshAppTheme.primary.withValues(alpha: 0.5),
            ),
          ),
          const SizedBox(height: 20),
          const Text(
            'No chats yet',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w700,
              color: MeshAppTheme.textWhite,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Add a peer from the "Pair" screen\nor create a group',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 14,
              color: MeshAppTheme.textDim,
              height: 1.5,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Contact tile ──

class _ContactTile extends StatelessWidget {
  const _ContactTile({required this.peer, this.unreadCount = 0});
  final Map<String, dynamic> peer;
  final int unreadCount;

  Color _avatarColor(int index) {
    const colors = [
      MeshAppTheme.primary,
      MeshAppTheme.info,
      Color(0xFF9C6ADE),
      Color(0xFFE07B53),
      MeshAppTheme.success,
    ];
    return colors[index.abs() % colors.length];
  }

  @override
  Widget build(BuildContext context) {
    final name = peer['displayName'] as String? ?? 'Peer';
    final deviceId = peer['deviceId'] as String? ?? '';
    final authorized = peer['authorized'] == true;
    final online = peer['online'] == true;
    final color = _avatarColor(deviceId.hashCode.abs());

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: authorized
            ? () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => ChatView(peerId: deviceId, peerName: name),
                ),
              )
            : () => ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Go to QR screen to pair')),
              ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            children: [
              Stack(
                children: [
                  CircleAvatar(
                    radius: 24,
                    backgroundColor: authorized
                        ? color.withValues(alpha: 0.15)
                        : MeshAppTheme.bgElevated,
                    child: authorized
                        ? Text(
                            name.isNotEmpty ? name[0].toUpperCase() : '?',
                            style: TextStyle(
                              color: color,
                              fontWeight: FontWeight.w800,
                              fontSize: 18,
                            ),
                          )
                        : Icon(Icons.person_outline_rounded, color: MeshAppTheme.textDim, size: 22),
                  ),
                  if (online)
                    Positioned(
                      right: 0,
                      bottom: 0,
                      child: Container(
                        width: 14,
                        height: 14,
                        decoration: BoxDecoration(
                          color: MeshAppTheme.success,
                          shape: BoxShape.circle,
                          border: Border.all(color: MeshAppTheme.bgDeep, width: 2),
                        ),
                      ),
                    ),
                  if (unreadCount > 0)
                    Positioned(
                      right: -2,
                      top: -2,
                      child: Container(
                        constraints: const BoxConstraints(minWidth: 20, minHeight: 20),
                        padding: const EdgeInsets.symmetric(horizontal: 5),
                        decoration: BoxDecoration(
                          color: MeshAppTheme.error,
                          shape: BoxShape.circle,
                          border: Border.all(color: MeshAppTheme.bgDeep, width: 2),
                        ),
                        child: Center(
                          child: Text(
                            unreadCount > 99 ? '99+' : '$unreadCount',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 10,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: MeshAppTheme.textWhite,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 3),
                    Row(
                      children: [
                        Container(
                          width: 6,
                          height: 6,
                          decoration: BoxDecoration(
                            color: authorized ? MeshAppTheme.success : MeshAppTheme.warning,
                            shape: BoxShape.circle,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          authorized ? 'Encrypted' : 'Pairing required',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                            color: authorized
                                ? MeshAppTheme.success
                                : MeshAppTheme.warning,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              if (authorized)
                Icon(Icons.chevron_right_rounded, color: MeshAppTheme.textDim, size: 22),
            ],
          ),
        ),
      ),
    );
  }
}

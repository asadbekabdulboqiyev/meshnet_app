import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/providers.dart';
import '../core/mesh_service.dart';
import '../theme/app_theme.dart';
import 'chat_view.dart';

/// Chats list — no gradient.
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
      if (event['event'] == 'messageReceived' || event['event'] == 'readReceipt') {
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

  @override
  Widget build(BuildContext context) {
    final peers = ref.watch(peersProvider).value ?? [];

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
      body: peers.isEmpty
          ? _EmptyState()
          : ListView.separated(
              itemCount: peers.length,
              separatorBuilder: (_, __) => Container(
                height: 1,
                color: MeshAppTheme.border,
                margin: const EdgeInsets.only(left: 72),
              ),
              itemBuilder: (context, i) => _ContactTile(
                peer: peers[i],
                unreadCount: _unreadCounts[peers[i]['deviceId'] as String? ?? ''] ?? 0,
              ),
            ),
    );
  }
}

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
            'Add a peer from the "Pair" screen\nvia QR code',
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
              // Avatar
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

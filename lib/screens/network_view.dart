import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/mesh_service.dart';
import '../core/providers.dart';
import '../theme/app_theme.dart';
import 'topology_view.dart';

/// Network screen — no gradient.
class NetworkView extends ConsumerWidget {
  const NetworkView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final service = ref.watch(meshServiceProvider);
    final peers = ref.watch(peersProvider).value ?? [];

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Container(
              width: 10,
              height: 10,
              decoration: const BoxDecoration(
                color: MeshAppTheme.success,
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 10),
            const Text('MeshNet'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.insights_rounded, size: 22),
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const TopologyView()),
              );
            },
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          await service.getPeers();
          ref.invalidate(peersProvider);
        },
        color: MeshAppTheme.primary,
        backgroundColor: MeshAppTheme.bgCard,
        child: ListView(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          children: [
            const SizedBox(height: 8),
            _StatusCard(peerCount: peers.length),
            const SizedBox(height: 24),
            Row(
              children: [
              Container(
                width: 4,
                height: 18,
                decoration: BoxDecoration(
                  color: MeshAppTheme.primary,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
                const SizedBox(width: 10),
                const Text(
                  'Network Members',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: MeshAppTheme.textWhite,
                  ),
                ),
                const Spacer(),
                Text(
                  '${peers.length}',
                  style: const TextStyle(
                    color: MeshAppTheme.primary,
                    fontWeight: FontWeight.w800,
                    fontSize: 14,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (peers.isEmpty)
              const _EmptyState()
            else
              ...peers.map((p) => _PeerTile(peer: p)),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.peerCount});
  final int peerCount;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: MeshAppTheme.bgCard,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: MeshAppTheme.border, width: 1),
      ),
      child: Row(
        children: [
          Container(
            width: 50,
            height: 50,
            decoration: BoxDecoration(
              color: MeshAppTheme.success.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(14),
            ),
            child: const Icon(Icons.wifi_tethering_rounded, color: MeshAppTheme.success, size: 26),
          ),
          const SizedBox(width: 16),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Mesh network active',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: MeshAppTheme.textWhite,
                  ),
                ),
                SizedBox(height: 4),
                Text(
                  'Offline communication • BLE + Wi-Fi Direct',
                  style: TextStyle(fontSize: 13, color: MeshAppTheme.textGray),
                ),
              ],
            ),
          ),
          Container(
            width: 8,
            height: 8,
            decoration: const BoxDecoration(
              color: MeshAppTheme.success,
              shape: BoxShape.circle,
            ),
          ),
        ],
      ),
    );
  }
}

class _PeerTile extends StatelessWidget {
  const _PeerTile({required this.peer});
  final Map<String, dynamic> peer;

  Color _getColor(int hash) {
    const colors = [
      MeshAppTheme.primary,
      MeshAppTheme.info,
      Color(0xFF9C6ADE),
      Color(0xFFE07B53),
      MeshAppTheme.success,
    ];
    return colors[hash.abs() % colors.length];
  }

  @override
  Widget build(BuildContext context) {
    final name = peer['displayName'] as String? ?? 'Peer';
    final authorized = peer['authorized'] == true;
    final quality = peer['linkQuality'] as int? ?? 50;
    final hop = peer['hop'] as int? ?? 0;
    final color = _getColor(name.hashCode);

    final qualityColor = quality >= 70
        ? MeshAppTheme.success
        : quality >= 40
            ? MeshAppTheme.warning
            : MeshAppTheme.error;

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: MeshAppTheme.bgCard,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: MeshAppTheme.border, width: 1),
      ),
      child: Row(
        children: [
          Container(
            width: 46,
            height: 46,
            decoration: BoxDecoration(
              color: authorized ? color.withValues(alpha: 0.12) : MeshAppTheme.bgElevated,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Center(
              child: authorized
                  ? Text(
                      name.isNotEmpty ? name[0].toUpperCase() : '?',
                      style: TextStyle(
                        color: color,
                        fontWeight: FontWeight.w800,
                        fontSize: 18,
                      ),
                    )
                  : Icon(Icons.person_outline_rounded, color: MeshAppTheme.textDim, size: 20),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: MeshAppTheme.textWhite,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  authorized
                      ? '${hop == 0 ? "Direct" : "$hop hops"} • ${quality >= 70 ? "Excellent" : quality >= 40 ? "Good" : "Weak"}'
                      : 'Pairing required',
                  style: TextStyle(
                    fontSize: 13,
                    color: authorized ? MeshAppTheme.textGray : MeshAppTheme.warning,
                    fontWeight: authorized ? FontWeight.w400 : FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
          // Quality bar
          Column(
            children: [
              Container(
                width: 36,
                height: 6,
                decoration: BoxDecoration(
                  color: MeshAppTheme.bgElevated,
                  borderRadius: BorderRadius.circular(3),
                ),
                child: FractionallySizedBox(
                  alignment: Alignment.centerLeft,
                  widthFactor: quality / 100,
                  child: Container(
                    decoration: BoxDecoration(
                      color: qualityColor,
                      borderRadius: BorderRadius.circular(3),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 4),
              Text(
                '$quality%',
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  color: qualityColor,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 48),
      child: Column(
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: MeshAppTheme.primary.withValues(alpha: 0.08),
            ),
            child: Icon(
              Icons.radar_rounded,
              size: 32,
              color: MeshAppTheme.primary.withValues(alpha: 0.4),
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'No peers found',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w700,
              color: MeshAppTheme.textWhite,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            'Will appear automatically when\nanother device is nearby',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 14, color: MeshAppTheme.textDim, height: 1.5),
          ),
        ],
      ),
    );
  }
}

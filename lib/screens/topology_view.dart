import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/providers.dart';
import '../theme/app_theme.dart';

/// Network topology — no gradient.
class TopologyView extends ConsumerWidget {
  const TopologyView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final info = ref.watch(nodeInfoProvider).value;
    final peers = ref.watch(peersProvider).value ?? [];

    return Scaffold(
      appBar: AppBar(
        title: const Text('Network Topology'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded, size: 22),
            onPressed: () {
              ref.invalidate(nodeInfoProvider);
              ref.invalidate(peersProvider);
            },
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(nodeInfoProvider);
          ref.invalidate(peersProvider);
        },
        color: MeshAppTheme.primary,
        backgroundColor: MeshAppTheme.bgCard,
        child: ListView(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          children: [
            const SizedBox(height: 12),
            _SectionTitle(title: 'Device'),
            const SizedBox(height: 10),
            if (info == null)
              const _SkeletonCard()
            else
              _DeviceCard(info: info),
            const SizedBox(height: 28),
            _SectionTitle(title: 'Neighbors'),
            const SizedBox(height: 10),
            if (peers.isEmpty)
              const _EmptyCard(text: 'No neighbors yet')
            else
              ...peers.map((p) => _PeerInfoTile(peer: p)),
            const SizedBox(height: 28),
            _SectionTitle(title: 'Routes'),
            const SizedBox(height: 10),
            if (info != null) _RouteList(info: info),
            const SizedBox(height: 28),
            _SectionTitle(title: 'Statistics'),
            const SizedBox(height: 10),
            if (info != null) _StatsGrid(info: info),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.title});
  final String title;

  @override
  Widget build(BuildContext context) {
    return Row(
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
        Text(
          title,
          style: const TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w700,
            color: MeshAppTheme.textWhite,
          ),
        ),
      ],
    );
  }
}

class _DeviceCard extends StatelessWidget {
  const _DeviceCard({required this.info});
  final Map<String, dynamic> info;

  @override
  Widget build(BuildContext context) {
    final name = info['displayName'] as String? ?? '-';
    final deviceId = info['deviceId'] as String? ?? '-';
    final running = info['running'] == true;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: MeshAppTheme.bgCard,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: MeshAppTheme.border, width: 1),
      ),
      child: Column(
        children: [
          _InfoRow(label: 'Name', value: name),
          _InfoRow(
            label: 'ID',
            value: deviceId.length > 8 ? '${deviceId.substring(0, 8)}...' : deviceId,
          ),
          _InfoRow(
            label: 'Status',
            value: running ? 'ACTIVE' : 'STOPPED',
            valueColor: running ? MeshAppTheme.success : MeshAppTheme.error,
          ),
        ],
      ),
    );
  }
}

class _PeerInfoTile extends StatelessWidget {
  const _PeerInfoTile({required this.peer});
  final Map<String, dynamic> peer;

  @override
  Widget build(BuildContext context) {
    final name = peer['displayName'] as String? ?? 'Peer';
    final transport = peer['transport'] as String? ?? '?';
    final rssi = peer['rssi'] as int? ?? 0;
    final hop = peer['hop'] as int? ?? 0;
    final quality = peer['linkQuality'] as int? ?? 50;
    final online = peer['online'] == true;

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
            width: 10,
            height: 10,
            decoration: BoxDecoration(
              color: online ? MeshAppTheme.success : MeshAppTheme.textDim,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: MeshAppTheme.textWhite,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  '$transport • RSSI $rssi • $hop hops',
                  style: TextStyle(fontSize: 12, color: MeshAppTheme.textDim),
                ),
              ],
            ),
          ),
          _QualityBadge(quality: quality, color: qualityColor),
        ],
      ),
    );
  }
}

class _QualityBadge extends StatelessWidget {
  const _QualityBadge({required this.quality, required this.color});
  final int quality;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        '$quality%',
        style: TextStyle(
          color: color,
          fontSize: 12,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }
}

class _RouteList extends StatelessWidget {
  const _RouteList({required this.info});
  final Map<String, dynamic> info;

  @override
  Widget build(BuildContext context) {
    final routes = info['routes'];
    if (routes is! List || routes.isEmpty) {
      return const _EmptyCard(text: 'No routes yet');
    }

    return Column(
      children: routes.map((r) {
        final dest = (r['destination'] as String? ?? '');
        final nextHop = (r['nextHop'] as String? ?? '');
        final hopCount = r['hopCount'] ?? 0;
        final quality = (r['quality'] as int?) ?? 0;

        final destShort = dest.length > 8 ? '${dest.substring(0, 8)}...' : dest;
        final hopShort = nextHop.length > 8 ? '${nextHop.substring(0, 8)}...' : nextHop;

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
              _QualityBadge(quality: quality, color: qualityColor),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      destShort,
                      style: const TextStyle(
                        fontSize: 13,
                        fontFamily: 'monospace',
                        color: MeshAppTheme.textWhite,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    Text(
                      'via $hopShort • $hopCount hops',
                      style: TextStyle(fontSize: 12, color: MeshAppTheme.textDim),
                    ),
                  ],
                ),
              ),
            ],
          ),
        );
      }).toList(),
    );
  }
}

class _StatsGrid extends StatelessWidget {
  const _StatsGrid({required this.info});
  final Map<String, dynamic> info;

  @override
  Widget build(BuildContext context) {
    final stats = info['stats'];
    if (stats is! Map) return const _EmptyCard(text: 'No statistics available');
    final s = stats.cast<String, dynamic>();

    final items = [
      ('RECEIVED', '${s['framesReceived'] ?? 0}', MeshAppTheme.primary),
      ('RELAYED', '${s['framesRelayed'] ?? 0}', MeshAppTheme.info),
      ('SENT', '${s['messagesSent'] ?? 0}', MeshAppTheme.warning),
      ('DELIVERED', '${s['messagesDelivered'] ?? 0}', MeshAppTheme.success),
      ('DROPPED', '${s['duplicatesDropped'] ?? 0}', MeshAppTheme.error),
      ('CACHE', '${s['seenCacheSize'] ?? 0}', MeshAppTheme.textGray),
      ('ROUTES', '${s['routeTableSize'] ?? 0}', MeshAppTheme.primary),
    ];

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: items.map((item) {
        return Container(
          width: (MediaQuery.of(context).size.width - 44) / 3,
          padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 12),
          decoration: BoxDecoration(
            color: MeshAppTheme.bgCard,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: MeshAppTheme.border, width: 1),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                item.$2,
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  color: item.$3,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                item.$1,
                style: const TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  color: MeshAppTheme.textDim,
                  letterSpacing: 0.5,
                ),
              ),
            ],
          ),
        );
      }).toList(),
    );
  }
}

class _EmptyCard extends StatelessWidget {
  const _EmptyCard({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: MeshAppTheme.bgCard,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: MeshAppTheme.border, width: 1),
      ),
      child: Center(
        child: Text(text, style: const TextStyle(fontSize: 14, color: MeshAppTheme.textDim)),
      ),
    );
  }
}

class _SkeletonCard extends StatelessWidget {
  const _SkeletonCard();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: MeshAppTheme.bgCard,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: MeshAppTheme.border, width: 1),
      ),
      child: const Center(
        child: CircularProgressIndicator(color: MeshAppTheme.primary, strokeWidth: 2),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value, this.valueColor});

  final String label;
  final String value;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(fontSize: 13, color: MeshAppTheme.textDim)),
          Text(
            value,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: valueColor ?? MeshAppTheme.textWhite,
            ),
          ),
        ],
      ),
    );
  }
}

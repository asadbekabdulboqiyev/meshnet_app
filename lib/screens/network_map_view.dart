import 'dart:math';
import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/providers.dart';
import '../widgets/network_map_painter.dart';

class NetworkMapView extends ConsumerStatefulWidget {
  const NetworkMapView({super.key});

  @override
  ConsumerState<NetworkMapView> createState() => _NetworkMapViewState();
}

class _NetworkMapViewState extends ConsumerState<NetworkMapView>
    with SingleTickerProviderStateMixin {
  late AnimationController _animController;
  final List<GraphNode> _nodes = [];
  final List<GraphEdge> _edges = [];
  double _scale = 1.0;
  Offset _panOffset = Offset.zero;
  GraphNode? _selectedNode;
  DateTime _lastForceUpdate = DateTime.now();

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 16),
    )..repeat();
    _animController.addListener(_onTick);
  }

  @override
  void dispose() {
    _animController.dispose();
    super.dispose();
  }

  void _onTick() {
    final now = DateTime.now();
    if (now.difference(_lastForceUpdate).inMilliseconds >= 100) {
      _applyForces();
      _lastForceUpdate = now;
    }
    if (mounted) setState(() {});
  }

  void _updateFromPeers(List<Map<String, dynamic>> peers) {
    final existingIds = _nodes.map((n) => n.id).toSet();

    if (!_nodes.any((n) => n.isSelf)) {
      _nodes.insert(0, GraphNode(
        id: 'self',
        name: 'Men',
        x: 300,
        y: 400,
        isSelf: true,
        quality: 100,
        hops: 0,
      ));
    }

    for (final peer in peers) {
      final id = peer['deviceId'] ?? '';
      if (id.isEmpty) continue;
      final existing = _nodes.where((n) => n.id == id);
      if (existing.isNotEmpty) {
        existing.first
          ..name = peer['displayName'] ?? 'Noma\'lum'
          ..quality = peer['linkQuality'] ?? 50
          ..hops = peer['hopDistance'] ?? 1
          ..isOnline = peer['online'] == true;
      } else {
        final angle = Random().nextDouble() * 2 * pi;
        final distance = 150.0 + Random().nextDouble() * 100;
        _nodes.add(GraphNode(
          id: id,
          name: peer['displayName'] ?? 'Noma\'lum',
          x: 300 + cos(angle) * distance,
          y: 400 + sin(angle) * distance,
          quality: peer['linkQuality'] ?? 50,
          hops: peer['hopDistance'] ?? 1,
          isOnline: peer['online'] == true,
        ));
      }
    }

    final peerIds = peers.map((p) => p['deviceId']?.toString() ?? '').toSet();
    _nodes.removeWhere((n) => !n.isSelf && !peerIds.contains(n.id));

    // BFS radius limit: only keep nodes within 3 hops of self.
    if (_nodes.any((n) => n.isSelf)) {
      _nodes.removeWhere((n) => !n.isSelf && n.hops > 3);
    }

    _edges.clear();
    final selfNode = _nodes.firstWhereOrNull((n) => n.isSelf);
    if (selfNode != null) {
      for (final node in _nodes) {
        if (node.isSelf) continue;
        if (_edges.any((e) =>
            (e.from == selfNode && e.to == node) ||
            (e.from == node && e.to == selfNode))) continue;
        _edges.add(GraphEdge(
          from: selfNode,
          to: node,
          quality: node.quality,
          hops: node.hops,
        ));
      }
    }
  }

  void _applyForces() {
    for (int i = 0; i < _nodes.length; i++) {
      for (int j = i + 1; j < _nodes.length; j++) {
        final a = _nodes[i];
        final b = _nodes[j];
        final dx = b.x - a.x;
        final dy = b.y - a.y;
        final dist = sqrt(dx * dx + dy * dy).clamp(1.0, 500.0);
        // Early skip: avoid force calculation for very distant node pairs.
        if (dist > 300) continue;
        final force = 5000.0 / (dist * dist);
        final fx = (dx / dist) * force;
        final fy = (dy / dist) * force;

        if (!a.isSelf) { a.x -= fx * 0.01; a.y -= fy * 0.01; }
        if (!b.isSelf) { b.x += fx * 0.01; b.y += fy * 0.01; }
      }
    }

    for (final edge in _edges) {
      final a = edge.from;
      final b = edge.to;
      final dx = b.x - a.x;
      final dy = b.y - a.y;
      final dist = sqrt(dx * dx + dy * dy).clamp(1.0, 500.0);
      final force = (dist - 150) * 0.005;
      final fx = (dx / dist) * force;
      final fy = (dy / dist) * force;

      if (!a.isSelf) { a.x += fx; a.y += fy; }
      if (!b.isSelf) { b.x -= fx; b.y -= fy; }
    }

    for (final node in _nodes) {
      if (!node.isSelf) {
        node.x += (300 - node.x) * 0.001;
        node.y += (400 - node.y) * 0.001;
      }
      node.x = node.x.clamp(50.0, 550.0);
      node.y = node.y.clamp(50.0, 750.0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final peersAsync = ref.watch(peersProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Tarmoq xaritasi'),
        actions: [
          if (_selectedNode != null)
            IconButton(
              icon: const Icon(Icons.close),
              onPressed: () => setState(() => _selectedNode = null),
            ),
        ],
      ),
      body: peersAsync.when(
        data: (peers) {
          _updateFromPeers(peers);
          return _buildCanvas();
        },
        loading: () => _buildCanvas(),
        error: (_, __) => _buildCanvas(),
      ),
    );
  }

  Widget _buildCanvas() {
    return GestureDetector(
      onScaleUpdate: (details) {
        setState(() {
          _scale = (_scale * details.scale).clamp(0.3, 3.0);
          _panOffset += details.focalPointDelta;
        });
      },
      onTapUp: (details) {
        final tapPos = (details.localPosition - _panOffset) / _scale;
        _selectedNode = _nodes.firstWhereOrNull(
          (n) => (Offset(n.x, n.y) - tapPos).distance < 35,
        );
        setState(() {});
      },
      child: CustomPaint(
        painter: NetworkMapPainter(
          nodes: _nodes,
          edges: _edges,
          scale: _scale,
          panOffset: _panOffset,
          selectedNode: _selectedNode,
        ),
        size: Size.infinite,
      ),
    );
  }
}

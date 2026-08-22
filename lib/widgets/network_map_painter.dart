import 'package:flutter/material.dart';

class GraphNode {
  String id;
  String name;
  double x, y;
  bool isSelf;
  int quality;
  int hops;
  bool isOnline;

  GraphNode({
    required this.id,
    required this.name,
    required this.x,
    required this.y,
    this.isSelf = false,
    this.quality = 50,
    this.hops = 0,
    this.isOnline = true,
  });
}

class GraphEdge {
  GraphNode from;
  GraphNode to;
  int quality;
  int hops;

  GraphEdge({
    required this.from,
    required this.to,
    this.quality = 50,
    this.hops = 1,
  });
}

class NetworkMapPainter extends CustomPainter {
  final List<GraphNode> nodes;
  final List<GraphEdge> edges;
  final double scale;
  final Offset panOffset;
  final GraphNode? selectedNode;

  NetworkMapPainter({
    required this.nodes,
    required this.edges,
    required this.scale,
    required this.panOffset,
    this.selectedNode,
  });

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.translate(panOffset.dx, panOffset.dy);
    canvas.scale(scale);

    // Viewport bounds in world coordinates — skip rendering nodes/edges
    // that fall outside the visible area (lazy rendering).
    final viewLeft = -panOffset.dx / scale;
    final viewTop = -panOffset.dy / scale;
    final viewRight = (size.width - panOffset.dx) / scale;
    final viewBottom = (size.height - panOffset.dy) / scale;
    // Add a margin so nodes/edges near the edge are not clipped abruptly.
    const margin = 100.0;
    final bounds = Rect.fromLTRB(
      viewLeft - margin,
      viewTop - margin,
      viewRight + margin,
      viewBottom + margin,
    );

    for (final edge in edges) {
      // Lazy: skip edges whose both endpoints are outside the viewport.
      final fromVisible = bounds.contains(Offset(edge.from.x, edge.from.y));
      final toVisible = bounds.contains(Offset(edge.to.x, edge.to.y));
      if (!fromVisible && !toVisible) continue;

      final paint = Paint()
        ..strokeWidth = 1.0 + (edge.quality / 50)
        ..color = _qualityColor(edge.quality).withValues(alpha: 0.6)
        ..style = PaintingStyle.stroke;
      canvas.drawLine(
        Offset(edge.from.x, edge.from.y),
        Offset(edge.to.x, edge.to.y),
        paint,
      );

      final midX = (edge.from.x + edge.to.x) / 2;
      final midY = (edge.from.y + edge.to.y) / 2;
      final textPainter = TextPainter(
        text: TextSpan(
          text: '${edge.hops}q',
          style: TextStyle(color: Colors.white54, fontSize: 9 / scale),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      textPainter.paint(canvas, Offset(midX - 6, midY - 8));
    }

    for (final node in nodes) {
      // Lazy: skip nodes outside the visible viewport.
      final nodeOffset = Offset(node.x, node.y);
      if (!bounds.contains(nodeOffset)) continue;
      _drawNode(canvas, node, size);
    }

    if (selectedNode != null) {
      _drawInfoPanel(canvas, selectedNode!, size);
    }

    canvas.restore();
  }

  void _drawNode(Canvas canvas, GraphNode node, Size size) {
    final radius = node.isSelf ? 28.0 : 20.0;
    final color = node.isSelf ? const Color(0xFF00C853) : _qualityColor(node.quality);

    if (node.isOnline) {
      final glowPaint = Paint()
        ..color = color.withValues(alpha: 0.3)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 15);
      canvas.drawCircle(Offset(node.x, node.y), radius + 8, glowPaint);
    }

    final mainPaint = Paint()..color = color;
    canvas.drawCircle(Offset(node.x, node.y), radius, mainPaint);

    if (node.isSelf) {
      canvas.drawCircle(
        Offset(node.x, node.y),
        radius - 4,
        Paint()..color = Colors.white..style = PaintingStyle.stroke..strokeWidth = 2,
      );
    }

    final textPainter = TextPainter(
      text: TextSpan(
        text: node.name.length > 8 ? '${node.name.substring(0, 8)}...' : node.name,
        style: TextStyle(
          color: Colors.white,
          fontSize: 10 / scale,
          fontWeight: FontWeight.w500,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    textPainter.paint(canvas, Offset(
      node.x - textPainter.width / 2,
      node.y + radius + 4,
    ));

    if (selectedNode == node) {
      final selectPaint = Paint()
        ..color = Colors.white.withValues(alpha: 0.3)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2;
      canvas.drawCircle(Offset(node.x, node.y), radius + 4, selectPaint);
    }
  }

  Color _qualityColor(int quality) {
    if (quality >= 70) return const Color(0xFF00C853);
    if (quality >= 40) return const Color(0xFFFFC107);
    return const Color(0xFFEF4444);
  }

  void _drawInfoPanel(Canvas canvas, GraphNode node, Size size) {
    final panelWidth = 180.0;
    final panelHeight = 100.0;
    final panelX = node.x - panelWidth / 2;
    final panelY = node.y + 40;

    final bgPaint = Paint()..color = const Color(0xDD1A1A1A);
    final rrect = RRect.fromRectAndRadius(
      Rect.fromLTWH(panelX, panelY, panelWidth, panelHeight),
      const Radius.circular(12),
    );
    canvas.drawRRect(rrect, bgPaint);

    final borderPaint = Paint()
      ..color = Colors.white24
      ..style = PaintingStyle.stroke;
    canvas.drawRRect(rrect, borderPaint);

    final lines = [
      node.name,
      'Signall: ${node.quality}%',
      'Masofa: ${node.hops} qadam',
      node.isOnline ? 'Online' : 'Offline',
    ];

    for (int i = 0; i < lines.length; i++) {
      final tp = TextPainter(
        text: TextSpan(
          text: lines[i],
          style: TextStyle(
            color: i == 3
                ? (node.isOnline ? const Color(0xFF66BB6A) : Colors.white38)
                : Colors.white,
            fontSize: 11 / scale,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      tp.paint(canvas, Offset(panelX + 12, panelY + 10 + i * 20.0));
    }
  }

  @override
  bool shouldRepaint(covariant NetworkMapPainter oldDelegate) => true;
}

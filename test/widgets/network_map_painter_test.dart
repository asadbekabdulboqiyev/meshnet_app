import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshnet_app/widgets/network_map_painter.dart';

void main() {
  group('GraphNode', () {
    test('creates with required fields and defaults', () {
      final node = GraphNode(id: 'n1', name: 'Node1', x: 10.0, y: 20.0);
      expect(node.id, 'n1');
      expect(node.name, 'Node1');
      expect(node.x, 10.0);
      expect(node.y, 20.0);
      expect(node.isSelf, isFalse);
      expect(node.quality, 50);
      expect(node.hops, 0);
      expect(node.isOnline, isTrue);
    });

    test('creates with all fields', () {
      final node = GraphNode(
        id: 'n1',
        name: 'Node1',
        x: 10.0,
        y: 20.0,
        isSelf: true,
        quality: 80,
        hops: 2,
        isOnline: false,
      );
      expect(node.isSelf, isTrue);
      expect(node.quality, 80);
      expect(node.hops, 2);
      expect(node.isOnline, isFalse);
    });

    test('fields are mutable', () {
      final node = GraphNode(id: 'n1', name: 'N', x: 0, y: 0);
      node.x = 100;
      node.y = 200;
      node.name = 'Updated';
      node.isSelf = true;
      node.quality = 99;
      node.hops = 5;
      node.isOnline = false;
      expect(node.x, 100);
      expect(node.y, 200);
      expect(node.name, 'Updated');
      expect(node.isSelf, isTrue);
      expect(node.quality, 99);
      expect(node.hops, 5);
      expect(node.isOnline, isFalse);
    });
  });

  group('GraphEdge', () {
    test('creates with required fields and defaults', () {
      final from = GraphNode(id: 'a', name: 'A', x: 0, y: 0);
      final to = GraphNode(id: 'b', name: 'B', x: 100, y: 100);
      final edge = GraphEdge(from: from, to: to);
      expect(edge.from, from);
      expect(edge.to, to);
      expect(edge.quality, 50);
      expect(edge.hops, 1);
    });

    test('creates with all fields', () {
      final from = GraphNode(id: 'a', name: 'A', x: 0, y: 0);
      final to = GraphNode(id: 'b', name: 'B', x: 100, y: 100);
      final edge = GraphEdge(from: from, to: to, quality: 80, hops: 3);
      expect(edge.quality, 80);
      expect(edge.hops, 3);
    });

    test('fields are mutable', () {
      final from = GraphNode(id: 'a', name: 'A', x: 0, y: 0);
      final to = GraphNode(id: 'b', name: 'B', x: 100, y: 100);
      final edge = GraphEdge(from: from, to: to);
      edge.quality = 90;
      edge.hops = 5;
      expect(edge.quality, 90);
      expect(edge.hops, 5);
    });
  });

  group('NetworkMapPainter', () {
    testWidgets('shouldRepaint always returns true', (tester) async {
      final nodes = [
        GraphNode(id: 'n1', name: 'N1', x: 50, y: 50),
      ];
      final painter = NetworkMapPainter(
        nodes: nodes,
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      final painter2 = NetworkMapPainter(
        nodes: nodes,
        edges: [],
        scale: 2.0,
        panOffset: Offset.zero,
      );

      expect(painter.shouldRepaint(painter2), isTrue);
    });

    testWidgets('shouldRepaint returns true for identical painters', (tester) async {
      final nodes = [
        GraphNode(id: 'n1', name: 'N1', x: 50, y: 50),
      ];
      final painter = NetworkMapPainter(
        nodes: nodes,
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      expect(painter.shouldRepaint(painter), isTrue);
    });

    testWidgets('can paint with empty nodes and edges', (tester) async {
      final painter = NetworkMapPainter(
        nodes: [],
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('can paint with nodes', (tester) async {
      final nodes = [
        GraphNode(id: 'n1', name: 'Node1', x: 100, y: 100, isSelf: true),
        GraphNode(id: 'n2', name: 'Node2', x: 200, y: 200),
      ];
      final painter = NetworkMapPainter(
        nodes: nodes,
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('can paint with edges', (tester) async {
      final n1 = GraphNode(id: 'n1', name: 'N1', x: 50, y: 50);
      final n2 = GraphNode(id: 'n2', name: 'N2', x: 200, y: 200);
      final edges = [GraphEdge(from: n1, to: n2, quality: 75, hops: 2)];
      final painter = NetworkMapPainter(
        nodes: [n1, n2],
        edges: edges,
        scale: 1.0,
        panOffset: Offset.zero,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('can paint with selectedNode', (tester) async {
      final node = GraphNode(id: 'n1', name: 'N1', x: 100, y: 100);
      final painter = NetworkMapPainter(
        nodes: [node],
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
        selectedNode: node,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('can paint with zoom and pan', (tester) async {
      final node = GraphNode(id: 'n1', name: 'N1', x: 100, y: 100);
      final painter = NetworkMapPainter(
        nodes: [node],
        edges: [],
        scale: 2.0,
        panOffset: const Offset(50, 50),
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('handles nodes outside viewport', (tester) async {
      // Node is very far from viewport
      final node = GraphNode(id: 'n1', name: 'Far', x: 5000, y: 5000);
      final painter = NetworkMapPainter(
        nodes: [node],
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('handles edges with both endpoints outside viewport', (tester) async {
      final n1 = GraphNode(id: 'n1', name: 'Far1', x: 5000, y: 5000);
      final n2 = GraphNode(id: 'n2', name: 'Far2', x: 6000, y: 6000);
      final painter = NetworkMapPainter(
        nodes: [n1, n2],
        edges: [GraphEdge(from: n1, to: n2)],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('long node name is truncated in display', (tester) async {
      final node = GraphNode(
        id: 'n1',
        name: 'VeryLongNodeName123',
        x: 100,
        y: 100,
      );
      final painter = NetworkMapPainter(
        nodes: [node],
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      // Just verifying it paints without error - truncation is visual
      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('handles all quality levels for nodes', (tester) async {
      final nodes = [
        GraphNode(id: 'high', name: 'High', x: 50, y: 50, quality: 80),
        GraphNode(id: 'med', name: 'Med', x: 100, y: 100, quality: 50),
        GraphNode(id: 'low', name: 'Low', x: 150, y: 150, quality: 10),
        GraphNode(id: 'zero', name: 'Zero', x: 200, y: 200, quality: 0),
        GraphNode(id: 'max', name: 'Max', x: 250, y: 250, quality: 100),
      ];
      final painter = NetworkMapPainter(
        nodes: nodes,
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('handles offline nodes', (tester) async {
      final node = GraphNode(
        id: 'n1',
        name: 'Offline',
        x: 100,
        y: 100,
        isOnline: false,
      );
      final painter = NetworkMapPainter(
        nodes: [node],
        edges: [],
        scale: 1.0,
        panOffset: Offset.zero,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });

    testWidgets('handles mixed quality edges', (tester) async {
      final n1 = GraphNode(id: 'n1', name: 'N1', x: 50, y: 50);
      final n2 = GraphNode(id: 'n2', name: 'N2', x: 150, y: 50);
      final n3 = GraphNode(id: 'n3', name: 'N3', x: 250, y: 50);
      final edges = [
        GraphEdge(from: n1, to: n2, quality: 90, hops: 1),
        GraphEdge(from: n2, to: n3, quality: 20, hops: 3),
      ];
      final painter = NetworkMapPainter(
        nodes: [n1, n2, n3],
        edges: edges,
        scale: 1.0,
        panOffset: Offset.zero,
      );

      await tester.pumpWidget(MaterialApp(
        home: CustomPaint(
          painter: painter,
          size: const Size(400, 400),
        ),
      ));

      expect(tester.takeException(), isNull);
    });
  });
}

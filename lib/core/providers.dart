import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'mesh_service.dart';

/// Peer list observation via Riverpod.
/// Refreshed on every mesh event (discovery/lost/update) — UI
/// automatically updates when a new node is found.
final peersProvider = StreamProvider<List<Map<String, dynamic>>>((ref) async* {
  final service = ref.watch(meshServiceProvider);
  yield await service.getPeers();
  await for (final _ in service.events) {
    yield await service.getPeers();
  }
});

/// Incoming messages — used by chat views.
final incomingMessagesProvider =
    StreamProvider<List<Map<String, dynamic>>>((ref) async* {
  final service = ref.watch(meshServiceProvider);
  final list = <Map<String, dynamic>>[];
  await for (final event in service.events) {
    if (event['event'] == 'messageReceived') {
      list.add(event);
      yield List.unmodifiable(list);
    }
  }
});

/// Node info (identity + peerCount + stats) — topology debug screen.
/// Refreshed on events and every 5 seconds.
final nodeInfoProvider = StreamProvider<Map<String, dynamic>>((ref) async* {
  final service = ref.watch(meshServiceProvider);
  while (true) {
    final info = await service.getNodeInfo();
    if (info != null) yield info;
    await Future<void>.delayed(const Duration(seconds: 5));
  }
});

final groupsProvider = StreamProvider<List<Map<String, dynamic>>>((ref) async* {
  final service = ref.watch(meshServiceProvider);
  yield await service.getGroups();
  await for (final _ in service.events) {
    yield await service.getGroups();
  }
});

final topologyProvider = StreamProvider<Map<String, dynamic>>((ref) async* {
  final service = ref.watch(meshServiceProvider);
  while (true) {
    try {
      final topology = await service.getTopology();
      if (topology != null) yield topology;
    } catch (_) {}
    await Future.delayed(const Duration(seconds: 3));
  }
});
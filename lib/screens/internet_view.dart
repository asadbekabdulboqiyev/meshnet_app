import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:meshnet_app/core/mesh_service.dart';

class InternetGatewayView extends ConsumerStatefulWidget {
  const InternetGatewayView({super.key});

  @override
  ConsumerState<InternetGatewayView> createState() => _InternetGatewayViewState();
}

class _InternetGatewayViewState extends ConsumerState<InternetGatewayView> {
  bool _starting = false;
  bool _running = false;
  int _port = 0;
  List<Map<String, dynamic>> _gateways = [];
  Map<String, dynamic>? _lastTest;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final svc = ref.read(meshServiceProvider);
    final running = await svc.getGateways().then((g) => g.any((e) => e['isSelf'] == true));
    final gateways = await svc.getGateways();
    if (mounted) {
      setState(() {
        _running = running;
        _gateways = gateways;
      });
    }
  }

  Future<void> _toggleGateway() async {
    final svc = ref.read(meshServiceProvider);
    if (_running) {
      final ok = await svc.stopInternetGateway();
      if (ok) {
        setState(() {
          _running = false;
          _port = 0;
        });
      }
    } else {
      setState(() => _starting = true);
      final res = await svc.startInternetGateway(port: 0);
      if (res['running'] == true) {
        setState(() {
          _running = true;
          _port = res['port'] as int? ?? 0;
        });
      }
      setState(() => _starting = false);
    }
    await _refresh();
  }

  Future<void> _testGateway(Map<String, dynamic> gw) async {
    final svc = ref.read(meshServiceProvider);
    final hostname = gw['hostname'] as String;
    final res = await svc.testGateway(hostname);
    if (mounted) {
      setState(() => _lastTest = res);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Internet Gateway'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _refresh,
            tooltip: 'Yangilash',
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // --- My Gateway Card ---
          Card(
            elevation: 0,
            color: theme.colorScheme.surfaceContainerHighest,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        _running ? Icons.wifi_tethering : Icons.wifi_tethering_off,
                        color: _running ? theme.colorScheme.primary : theme.colorScheme.onSurfaceVariant,
                        size: 28,
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Share My Internet',
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            Text(
                              _running
                                  ? 'Proxy port: $_port — CONNECT & HTTP forward'
                                  : 'O\'z internetingizni mesh tarmoqqa oching',
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      ),
                      FilledButton.tonal(
                        onPressed: _starting ? null : _toggleGateway,
                        child: _starting
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : Text(_running ? 'Stop' : 'Start'),
                      ),
                    ],
                  ),
                  if (_running) ...[
                    const SizedBox(height: 12),
                    const Divider(height: 1),
                    const SizedBox(height: 12),
                    _GatewayStatRow(
                      label: 'Active tunnels',
                      value: _gateways
                          .where((g) => g['isSelf'] == true)
                          .map((g) => g['activeTunnels'] as int? ?? 0)
                          .fold(0, (a, b) => a + b)
                          .toString(),
                    ),
                    _GatewayStatRow(
                      label: 'Total connections',
                      value: _gateways
                          .where((g) => g['isSelf'] == true)
                          .map((g) => g['totalConnections'] as int? ?? 0)
                          .fold(0, (a, b) => a + b)
                          .toString(),
                    ),
                    _GatewayStatRow(
                      label: 'Bytes → target',
                      value: _gateways
                          .where((g) => g['isSelf'] == true)
                          .map((g) => g['bytesToTarget'] as int? ?? 0)
                          .fold(0, (a, b) => a + b)
                          .toString(),
                    ),
                    _GatewayStatRow(
                      label: 'Bytes ← target',
                      value: _gateways
                          .where((g) => g['isSelf'] == true)
                          .map((g) => g['bytesFromTarget'] as int? ?? 0)
                          .fold(0, (a, b) => a + b)
                          .toString(),
                    ),
                    _GatewayStatRow(
                      label: 'Denied requests',
                      value: _gateways
                          .where((g) => g['isSelf'] == true)
                          .map((g) => g['denied'] as int? ?? 0)
                          .fold(0, (a, b) => a + b)
                          .toString(),
                    ),
                  ],
                ],
              ),
            ),
          ),

          const SizedBox(height: 16),

          // --- Discovered Gateways ---
          Text(
            'Discovered Gateways',
            style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 8),

          if (_gateways.where((g) => g['isSelf'] != true).isEmpty)
            Card(
              color: theme.colorScheme.surfaceContainerHighest,
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Center(
                  child: Column(
                    children: [
                      Icon(
                        Icons.devices_rounded,
                        size: 48,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'No gateways discovered yet',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Peer devices running Internet Gateway will appear here',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ],
                  ),
                ),
              ),
            )
          else
            ListView.separated(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              itemCount: _gateways.where((g) => g['isSelf'] != true).length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, index) {
                final gw = _gateways.where((g) => g['isSelf'] != true).toList()[index];
                return _GatewayCard(
                  gateway: gw,
                  onTest: () => _testGateway(gw),
                  lastTest: _lastTest?['hostname'] == gw['hostname'] ? _lastTest : null,
                );
              },
            ),

          const SizedBox(height: 16),

          // --- Usage Hint ---
          Card(
            color: theme.colorScheme.primaryContainer,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.info_outline, color: theme.colorScheme.onPrimaryContainer),
                      const SizedBox(width: 8),
                      Text(
                        'How to use',
                        style: theme.textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w600,
                          color: theme.colorScheme.onPrimaryContainer,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '• Start "Share My Internet" on one device\n'
                    '• Other devices will discover it automatically\n'
                    '• Configure your app/browser proxy to: <gateway-host>:<proxy-port>\n'
                    '• Supports HTTP CONNECT (HTTPS) and plain HTTP forward\n'
                    '• This is NOT a system-wide VPN — only proxy-aware apps work',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onPrimaryContainer,
                      height: 1.5,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _GatewayCard extends StatelessWidget {
  final Map<String, dynamic> gateway;
  final VoidCallback onTest;
  final Map<String, dynamic>? lastTest;

  const _GatewayCard({
    required this.gateway,
    required this.onTest,
    this.lastTest,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final hostname = gateway['hostname'] as String? ?? 'unknown';
    final deviceId = gateway['deviceId'] as String? ?? '';
    final proxyPort = gateway['proxyPort'] as int? ?? 0;
    final activeTunnels = gateway['activeTunnels'] as int? ?? 0;
    final isReachable = lastTest?['reachable'] == true;
    final latency = lastTest?['latencyMs'] as int?;

    return Card(
      elevation: 0,
      color: theme.colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.router,
                  color: theme.colorScheme.primary,
                  size: 24,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        hostname,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Text(
                        'device: ${deviceId.substring(0, 8)}... • port $proxyPort',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                if (lastTest != null)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: isReachable
                          ? theme.colorScheme.tertiaryContainer
                          : theme.colorScheme.errorContainer,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      isReachable
                          ? 'OK ${latency != null ? "${latency}ms" : ""}'
                          : 'Unreachable',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: isReachable
                            ? theme.colorScheme.onTertiaryContainer
                            : theme.colorScheme.onErrorContainer,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
              ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                _StatChip(
                  label: 'Tunnels',
                  value: activeTunnels.toString(),
                  icon: Icons.account_tree,
                ),
                const SizedBox(width: 8),
                _StatChip(
                  label: 'Proxy',
                  value: '$proxyPort',
                  icon: Icons.settings_ethernet,
                ),
              ],
            ),
            const SizedBox(height: 12),
            FilledButton.tonal(
              onPressed: onTest,
              child: const Text('Test Connection'),
            ),
          ],
        ),
      ),
    );
  }
}

class _GatewayStatRow extends StatelessWidget {
  final String label;
  final String value;

  const _GatewayStatRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Text(
            value,
            style: theme.textTheme.bodySmall?.copyWith(
              fontWeight: FontWeight.w600,
              fontFamily: 'monospace',
            ),
          ),
        ],
      ),
    );
  }
}

class _StatChip extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;

  const _StatChip({
    required this.label,
    required this.value,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: theme.colorScheme.onSurfaceVariant),
          const SizedBox(width: 6),
          Text(
            '$label: $value',
            style: theme.textTheme.bodySmall?.copyWith(
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}
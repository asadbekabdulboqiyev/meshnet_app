import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';
import '../theme/app_theme.dart';

const _method = MethodChannel('meshnet/engine');

/// Self-diagnostics screen showing system health, connectivity, and log export
class DiagnosticsView extends ConsumerStatefulWidget {
  const DiagnosticsView({super.key});

  @override
  ConsumerState<DiagnosticsView> createState() => _DiagnosticsViewState();
}

class _DiagnosticsViewState extends ConsumerState<DiagnosticsView> {
  Map<String, dynamic>? _diagnostics;
  List<String> _logs = [];
  bool _loading = true;
  bool _exporting = false;

  @override
  void initState() {
    super.initState();
    _loadDiagnostics();
    _loadLogs();
  }

  Future<void> _loadDiagnostics() async {
    setState(() => _loading = true);
    try {
      // Get various diagnostics from the mesh engine
      final diag = await _method.invokeMethod('getDiagnostics');
      setState(() {
        _diagnostics = Map<String, dynamic>.from(diag);
        _loading = false;
      });
    } catch (e) {
      setState(() => _loading = false);
    }
  }

  Future<void> _loadLogs() async {
    try {
      final result = await _method.invokeMethod('getLogs', {'lines': 500});
      if (result is List) {
        setState(() => _logs = List<String>.from(result));
      }
    } catch (e) {
      // Ignore
    }
  }

  Future<void> _exportLogs() async {
    setState(() => _exporting = true);
    try {
      final logs = _logs.join('\n');
      await Share.share(
        logs,
        subject: 'MeshNet Diagnostics Logs',
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Export failed: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  Future<void> _refresh() async {
    await _loadDiagnostics();
    await _loadLogs();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Diagnostics'),
        actions: [
          IconButton(
            icon: _loading ? const SizedBox(
              width: 20, height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ) : const Icon(Icons.refresh_rounded),
            onPressed: _loading ? null : _refresh,
            tooltip: 'Refresh',
          ),
          IconButton(
            icon: _exporting 
              ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.file_download_rounded),
            onPressed: _exporting || _logs.isEmpty ? null : _exportLogs,
            tooltip: 'Export Logs',
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  // Engine Status Card
                  _DiagnosticCard(
                    title: 'Engine Status',
                    icon: Icons.settings_ethernet_rounded,
                    children: [
                      _DiagnosticRow(
                        label: 'Engine Running',
                        value: _diagnostics?['running'] == true ? 'Yes' : 'No',
                        icon: _diagnostics?['running'] == true ? Icons.check_circle_rounded : Icons.error_rounded,
                        color: _diagnostics?['running'] == true ? MeshAppTheme.success : MeshAppTheme.error,
                      ),
                      _DiagnosticRow(
                        label: 'Device ID',
                        value: _diagnostics?['deviceId']?.toString() ?? 'Unknown',
                        icon: Icons.fingerprint_rounded,
                      ),
                      _DiagnosticRow(
                        label: 'Display Name',
                        value: _diagnostics?['displayName'] ?? 'Unknown',
                        icon: Icons.person_rounded,
                      ),
                      _DiagnosticRow(
                        label: 'Uptime',
                        value: _formatUptime(_diagnostics?['uptimeMs']),
                        icon: Icons.access_time_rounded,
                      ),
                      _DiagnosticRow(
                        label: 'Version',
                        value: _diagnostics?['version'] ?? 'Unknown',
                        icon: Icons.info_outline_rounded,
                      ),
                    ],
                  ),

                  const SizedBox(height: 16),

                  // Transport Status
                  _DiagnosticCard(
                    title: 'Transports',
                    icon: Icons.router_rounded,
                    children: [
                      _DiagnosticRow(
                        label: 'BLE',
                        value: _diagnostics?['ble']?['enabled'] == true ? 'Enabled' : 'Disabled',
                        icon: _diagnostics?['ble']?['enabled'] == true ? Icons.bluetooth_connected_rounded : Icons.bluetooth_disabled_rounded,
                        color: _diagnostics?['ble']?['enabled'] == true ? MeshAppTheme.success : MeshAppTheme.warning,
                        subtitle: _diagnostics?['ble']?['connectedPeers'] != null 
                            ? '${_diagnostics!['ble']['connectedPeers']} connected' : null,
                      ),
                      _DiagnosticRow(
                        label: 'Wi-Fi Direct',
                        value: _diagnostics?['wifiDirect']?['enabled'] == true ? 'Enabled' : 'Disabled',
                        icon: _diagnostics?['wifiDirect']?['enabled'] == true ? Icons.wifi_rounded : Icons.wifi_off_rounded,
                        color: _diagnostics?['wifiDirect']?['enabled'] == true ? MeshAppTheme.success : MeshAppTheme.warning,
                        subtitle: _diagnostics?['wifiDirect']?['groupOwner'] == true 
                            ? 'Group Owner' : (_diagnostics?['wifiDirect']?['connectedPeers'] != null 
                                ? '${_diagnostics!['wifiDirect']['connectedPeers']} peers' : null),
                      ),
                    ],
                  ),

                  const SizedBox(height: 16),

                  // Mesh Network
                  _DiagnosticCard(
                    title: 'Mesh Network',
                    icon: Icons.hub_rounded,
                    children: [
                      _DiagnosticRow(
                        label: 'Peers Connected',
                        value: '${_diagnostics?['peersConnected'] ?? 0}',
                        icon: Icons.people_rounded,
                        color: MeshAppTheme.primary,
                      ),
                      _DiagnosticRow(
                        label: 'Peers Authorized',
                        value: '${_diagnostics?['peersAuthorized'] ?? 0}',
                        icon: Icons.lock_rounded,
                        color: MeshAppTheme.success,
                      ),
                      _DiagnosticRow(
                        label: 'Messages Sent',
                        value: '${_diagnostics?['messagesSent'] ?? 0}',
                        icon: Icons.send_rounded,
                      ),
                      _DiagnosticRow(
                        label: 'Messages Received',
                        value: '${_diagnostics?['messagesReceived'] ?? 0}',
                        icon: Icons.inbox_rounded,
                      ),
                      _DiagnosticRow(
                        label: 'Messages Failed',
                        value: '${_diagnostics?['messagesFailed'] ?? 0}',
                        icon: Icons.error_outline_rounded,
                        color: MeshAppTheme.error,
                      ),
                      _DiagnosticRow(
                        label: 'Outbox Queue',
                        value: '${_diagnostics?['outboxSize'] ?? 0}',
                        icon: Icons.queue_rounded,
                        color: MeshAppTheme.warning,
                      ),
                    ],
                  ),

                  const SizedBox(height: 16),

                  // LocalNet
                  if (_diagnostics?['localNet'] != null)
                    _DiagnosticCard(
                      title: 'LocalNet Services',
                      icon: Icons.dns_rounded,
                      children: [
                        _DiagnosticRow(
                          label: 'Hostname',
                          value: '${_diagnostics!['localNet']['hostname'] ?? 'Unknown'}.mesh',
                          icon: Icons.dns_rounded,
                        ),
                        _DiagnosticRow(
                          label: 'HTTP Server',
                          value: _diagnostics!['localNet']['httpPort'] != null 
                              ? 'Running on port ${_diagnostics!['localNet']['httpPort']}' 
                              : 'Stopped',
                          icon: _diagnostics!['localNet']['httpPort'] != null 
                              ? Icons.cloud_done_rounded 
                              : Icons.cloud_off_rounded,
                          color: _diagnostics!['localNet']['httpPort'] != null 
                              ? MeshAppTheme.success : MeshAppTheme.warning,
                        ),
                        _DiagnosticRow(
                          label: 'Known Hosts',
                          value: '${_diagnostics!['localNet']['hostsCount'] ?? 0}',
                          icon: Icons.public_rounded,
                        ),
                        _DiagnosticRow(
                          label: 'Gateway Active',
                          value: _diagnostics!['localNet']['gatewayRunning'] == true ? 'Yes' : 'No',
                          icon: _diagnostics!['localNet']['gatewayRunning'] == true 
                              ? Icons.wifi_tethering_rounded 
                              : Icons.wifi_tethering_off_rounded,
                          color: _diagnostics!['localNet']['gatewayRunning'] == true 
                              ? MeshAppTheme.success : MeshAppTheme.warning,
                        ),
                      ],
                    ),

                  const SizedBox(height: 16),

                  // Emergency
                  if (_diagnostics?['emergency'] != null)
                    _DiagnosticCard(
                      title: 'Emergency Broadcast',
                      icon: Icons.warning_amber_rounded,
                      children: [
                        _DiagnosticRow(
                          label: 'Active Alerts',
                          value: '${_diagnostics!['emergency']['activeAlerts'] ?? 0}',
                          icon: Icons.notification_important_rounded,
                          color: _diagnostics!['emergency']['activeAlerts'] > 0 ? MeshAppTheme.error : MeshAppTheme.success,
                        ),
                        _DiagnosticRow(
                          label: 'Pending ACKs',
                          value: '${_diagnostics!['emergency']['pendingAcks'] ?? 0}',
                          icon: Icons.hourglass_empty_rounded,
                        ),
                      ],
                    ),

                  const SizedBox(height: 16),

                  // Logs
                  _DiagnosticCard(
                    title: 'Recent Logs (${_logs.length} lines)',
                    icon: Icons.article_rounded,
                    children: [
                      if (_logs.isEmpty)
                        _DiagnosticRow(
                          label: 'No logs available',
                          value: 'Run the app to generate logs',
                          icon: Icons.info_outline_rounded,
                          color: MeshAppTheme.textDim,
                        )
                      else
                        ..._logs.take(10).map((log) => _LogRow(log: log)),
                      if (_logs.length > 10)
                        _DiagnosticRow(
                          label: '... and ${_logs.length - 10} more lines',
                          value: 'Export to see all',
                          icon: Icons.expand_more_rounded,
                          color: MeshAppTheme.textDim,
                        ),
                    ],
                  ),

                  const SizedBox(height: 24),

                  // Actions
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          icon: const Icon(Icons.bug_report_rounded),
                          label: const Text('Export All Logs'),
                          onPressed: _logs.isEmpty ? null : _exportLogs,
                          style: OutlinedButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 16),
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: FilledButton.icon(
                          icon: const Icon(Icons.delete_sweep_rounded),
                          label: const Text('Clear Local Logs'),
                          onPressed: _logs.isEmpty ? null : _clearLogs,
                          style: FilledButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            backgroundColor: MeshAppTheme.error,
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
    );
  }

  String _formatUptime(int? ms) {
    if (ms == null) return 'Unknown';
    final s = ms ~/ 1000;
    if (s < 60) return '${s}s';
    final m = s ~/ 60;
    if (m < 60) return '${m}m ${s % 60}s';
    final h = m ~/ 60;
    return '${h}h ${m % 60}m';
  }

  Future<void> _clearLogs() async {
    try {
      await _method.invokeMethod('clearLogs');
      setState(() => _logs.clear());
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Logs cleared')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to clear logs: $e')),
        );
      }
    }
  }
}

class _DiagnosticCard extends StatelessWidget {
  final String title;
  final IconData icon;
  final List<Widget> children;

  const _DiagnosticCard({
    required this.title,
    required this.icon,
    required this.children,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      elevation: 0,
      color: theme.colorScheme.surfaceContainerHighest,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: theme.colorScheme.primary, size: 22),
                const SizedBox(width: 10),
                Text(
                  title,
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            ...children,
          ],
        ),
      ),
    );
  }
}

class _DiagnosticRow extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final Color? color;
  final String? subtitle;

  const _DiagnosticRow({
    required this.label,
    required this.value,
    required this.icon,
    this.color,
    this.subtitle,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Icon(icon, size: 20, color: color ?? theme.colorScheme.onSurfaceVariant),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                Text(
                  value,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                    color: color ?? theme.colorScheme.onSurface,
                  ),
                ),
                if (subtitle != null) ...[
                  const SizedBox(height: 2),
                  Text(
                    subtitle!,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _LogRow extends StatelessWidget {
  final String log;

  const _LogRow({required this.log});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: SelectableText(
        log,
        style: theme.textTheme.bodySmall?.copyWith(
          fontFamily: 'monospace',
          fontSize: 11,
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}
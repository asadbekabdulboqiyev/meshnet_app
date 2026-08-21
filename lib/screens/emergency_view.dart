import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:meshnet_app/core/mesh_service.dart';
import 'package:meshnet_app/theme/app_theme.dart';

class EmergencyView extends ConsumerStatefulWidget {
  const EmergencyView({super.key});

  @override
  ConsumerState<EmergencyView> createState() => _EmergencyViewState();
}

class _EmergencyViewState extends ConsumerState<EmergencyView> {
  List<Map<String, dynamic>> _alerts = [];
  bool _loading = true;
  final _titleCtrl = TextEditingController();
  final _messageCtrl = TextEditingController();
  final _locationCtrl = TextEditingController();
  final _coordsCtrl = TextEditingController();
  int _selectedLevel = 2; // WARNING default
  int _ttlMinutes = 60;
  bool _requiresAck = true;
  bool _sending = false;

  @override
  void initState() {
    super.initState();
    _loadAlerts();
    _subscribe();
  }

  @override
  void dispose() {
    _titleCtrl.dispose();
    _messageCtrl.dispose();
    _locationCtrl.dispose();
    _coordsCtrl.dispose();
    super.dispose();
  }

  void _subscribe() {
    ref.read(meshServiceProvider).events.listen((event) {
      final type = event['event'];
      if (type == 'emergencyAlert' || type == 'emergencyAck' || type == 'emergencyCancelled') {
        _loadAlerts();
      }
    });
  }

  Future<void> _loadAlerts() async {
    setState(() => _loading = true);
    final alerts = await ref.read(meshServiceProvider).getEmergencies();
    if (mounted) setState(() { _alerts = alerts; _loading = false; });
  }

  Future<void> _sendAlert() async {
    if (_titleCtrl.text.trim().isEmpty || _messageCtrl.text.trim().isEmpty) return;
    setState(() => _sending = true);
    final res = await ref.read(meshServiceProvider).sendEmergencyAlert(
      level: _selectedLevel,
      title: _titleCtrl.text.trim(),
      message: _messageCtrl.text.trim(),
      location: _locationCtrl.text.trim().isEmpty ? null : _locationCtrl.text.trim(),
      coordinates: _coordsCtrl.text.trim().isEmpty ? null : _coordsCtrl.text.trim(),
      ttlMinutes: _ttlMinutes,
      requiresAck: _requiresAck,
    );
    setState(() => _sending = false);
    if (res != null && mounted) {
      _titleCtrl.clear();
      _messageCtrl.clear();
      _locationCtrl.clear();
      _coordsCtrl.clear();
      _loadAlerts();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Alert sent: ${res['alertId']}'), behavior: SnackBarBehavior.floating),
      );
    }
  }

  Future<void> _acknowledge(String alertId) async {
    final ok = await ref.read(meshServiceProvider).acknowledgeEmergency(alertId);
    if (ok && mounted) {
      _loadAlerts();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Acknowledged'), behavior: SnackBarBehavior.floating),
      );
    }
  }

  Future<void> _cancel(String alertId) async {
    final ok = await ref.read(meshServiceProvider).cancelEmergency(alertId);
    if (ok && mounted) {
      _loadAlerts();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Cancelled'), behavior: SnackBarBehavior.floating),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Emergency Broadcast'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: _loadAlerts),
        ],
      ),
      body: Column(
        children: [
          // Send Alert Card
          Card(
            margin: const EdgeInsets.all(16),
            color: theme.colorScheme.errorContainer.withValues(alpha: 0.1),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Send Emergency Alert', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<int>(
                    value: _selectedLevel,
                    decoration: const InputDecoration(labelText: 'Level', border: OutlineInputBorder()),
                    items: const [
                      DropdownMenuItem(value: 1, child: Text('Info (Blue)')),
                      DropdownMenuItem(value: 2, child: Text('Warning (Orange)')),
                      DropdownMenuItem(value: 3, child: Text('Critical (Red)')),
                      DropdownMenuItem(value: 4, child: Text('Emergency (Dark Red)')),
                    ],
                    onChanged: (v) => setState(() => _selectedLevel = v ?? 2),
                  ),
                  const SizedBox(height: 8),
                  TextField(controller: _titleCtrl, decoration: const InputDecoration(labelText: 'Title', border: OutlineInputBorder())),
                  const SizedBox(height: 8),
                  TextField(controller: _messageCtrl, decoration: const InputDecoration(labelText: 'Message', border: OutlineInputBorder()), maxLines: 3),
                  const SizedBox(height: 8),
                  TextField(controller: _locationCtrl, decoration: const InputDecoration(labelText: 'Location (optional)', border: OutlineInputBorder())),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(child: TextField(controller: _coordsCtrl, decoration: const InputDecoration(labelText: 'Lat,Lon (optional)', border: OutlineInputBorder()))),
                      const SizedBox(width: 8),
                      SizedBox(width: 100, child: TextField(keyboardType: TextInputType.number, controller: TextEditingController(text: _ttlMinutes.toString()), decoration: const InputDecoration(labelText: 'TTL (min)', border: OutlineInputBorder()), onChanged: (v) => _ttlMinutes = int.tryParse(v) ?? 60)),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Checkbox(value: _requiresAck, onChanged: (v) => setState(() => _requiresAck = v ?? true)),
                      const Text('Requires acknowledgment'),
                      const Spacer(),
                      FilledButton(onPressed: _sending ? null : _sendAlert, child: _sending ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Broadcast')),
                    ],
                  ),
                ],
              ),
            ),
          ),

          // Alerts List
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _alerts.isEmpty
                    ? Center(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(Icons.warning_amber_rounded, size: 64, color: theme.colorScheme.onSurfaceVariant),
                            const SizedBox(height: 16),
                            Text('No active alerts', style: theme.textTheme.titleMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                            const SizedBox(height: 8),
                            Text('Emergency broadcasts from the mesh will appear here', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant), textAlign: TextAlign.center),
                          ],
                        ),
                      )
                    : ListView.builder(
                        padding: const EdgeInsets.all(16),
                        itemCount: _alerts.length,
                        itemBuilder: (context, index) => _AlertCard(
                          alert: _alerts[index],
                          onAcknowledge: _acknowledge,
                          onCancel: _cancel,
                          isSelf: _alerts[index]['senderId'] == ref.read(meshServiceProvider).getLocalIdentity().then((id) => id?['deviceId']),
                        ),
                      ),
          ),
        ],
      ),
    );
  }
}

class _AlertCard extends ConsumerWidget {
  final Map<String, dynamic> alert;
  final Function(String) onAcknowledge;
  final Function(String) onCancel;
  final bool isSelf;

  const _AlertCard({required this.alert, required this.onAcknowledge, required this.onCancel, this.isSelf = false});

  static const _levelInfo = {
    'INFO': {'label': 'Info', 'color': 0xFF2196F3},
    'WARNING': {'label': 'Warning', 'color': 0xFFFF9800},
    'CRITICAL': {'label': 'Critical', 'color': 0xFFF44336},
    'EMERGENCY': {'label': 'Emergency', 'color': 0xFFB71C1C},
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final levelName = alert['level'] as String? ?? 'WARNING';
    final level = _levelInfo[levelName] ?? _levelInfo['WARNING']!;
    final color = Color(level['color'] as int);
    final expiresAt = alert['expiresAtMs'] as int? ?? 0;
    final remaining = (expiresAt - DateTime.now().millisecondsSinceEpoch).clamp(0, double.infinity).toInt();
    final requiresAck = alert['requiresAck'] == true;

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      elevation: 0,
      color: theme.colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(8)),
                  child: Text(level['label'] as String, style: theme.textTheme.labelSmall?.copyWith(color: Colors.white, fontWeight: FontWeight.w600)),
                ),
                const SizedBox(width: 8),
                if (isSelf) Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(color: theme.colorScheme.primaryContainer, borderRadius: BorderRadius.circular(8)),
                  child: Text('YOURS', style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onPrimaryContainer, fontWeight: FontWeight.w600)),
                ),
                const Spacer(),
                Text(_formatDuration(remaining), style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'monospace', color: theme.colorScheme.onSurfaceVariant)),
              ],
            ),
            const SizedBox(height: 8),
            Text(alert['title'] ?? '', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Text(alert['message'] ?? '', style: theme.textTheme.bodyMedium),
            if (alert['location'] != null) ...[
              const SizedBox(height: 4),
              Row(children: [Icon(Icons.location_on, size: 14, color: theme.colorScheme.onSurfaceVariant), const SizedBox(width: 4), Text(alert['location'], style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant))]),
            ],
            if (alert['coordinates'] != null) ...[
              const SizedBox(height: 4),
              Row(children: [Icon(Icons.gps_fixed, size: 14, color: theme.colorScheme.onSurfaceVariant), const SizedBox(width: 4), Text(alert['coordinates'], style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant, fontFamily: 'monospace'))]),
            ],
            const SizedBox(height: 12),
            Row(
              children: [
                Text('From: ${alert['senderName'] ?? alert['senderId']?.substring(0, 8)}', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                const Spacer(),
                if (requiresAck)
                  OutlinedButton(onPressed: () => onAcknowledge(alert['alertId']), child: const Text('Acknowledge')),
                if (isSelf) ...[
                  const SizedBox(width: 8),
                  TextButton(onPressed: () => onCancel(alert['alertId']), child: const Text('Cancel')),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  String _formatDuration(int ms) {
    if (ms <= 0) return 'Expired';
    final s = ms ~/ 1000;
    if (s < 60) return '\${s}s';
    final m = s ~/ 60;
    if (m < 60) return '\${m}m \${s % 60}s';
    final h = m ~/ 60;
    return '\${h}h \${m % 60}m';
  }
}
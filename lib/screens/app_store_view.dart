import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/mesh_service.dart';
import '../theme/app_theme.dart';

/// Phase 4: offline app distribution over the mesh.
///
///  - "My Apps": APKs this device shares (with parsed package info)
///  - "From Hosts": browse a discovered host's APKs, download, install
///
/// Honest limits shown in UI: remote apps have no package info until
/// downloaded; install goes through the Android system installer with
/// full user consent.
class AppStoreView extends ConsumerStatefulWidget {
  const AppStoreView({super.key});

  @override
  ConsumerState<AppStoreView> createState() => _AppStoreViewState();
}

class _AppStoreViewState extends ConsumerState<AppStoreView> {
  List<Map<String, dynamic>> _myApps = [];
  List<String> _hosts = [];
  String? _selectedHost;
  List<Map<String, dynamic>> _hostApps = [];
  bool _loadingHostApps = false;
  final Map<String, double> _progress = {}; // fileId -> 0..1
  final Set<String> _downloaded = {}; // fileIds ready to install
  StreamSubscription<Map<String, dynamic>>? _sub;

  @override
  void initState() {
    super.initState();
    _refreshMyApps();
    _loadHosts();
    _sub = ref.read(meshServiceProvider).events.listen(_onEvent);
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  void _onEvent(Map<String, dynamic> event) {
    if (!mounted) return;
    switch (event['event']) {
      case 'fileSyncProgress':
        final fileId = event['fileId'] as String?;
        if (fileId == null) return;
        final state = event['state'] as String?;
        setState(() {
          if (state == 'done') {
            _progress.remove(fileId);
            _downloaded.add(fileId);
          } else if (state == 'failed') {
            _progress.remove(fileId);
          } else {
            final total = event['total'] as int? ?? 0;
            _progress[fileId] =
                total > 0 ? (event['have'] as int? ?? 0) / total : 0;
          }
        });
      case 'appReady':
        final fileId = event['fileId'] as String?;
        if (fileId == null) return;
        setState(() => _downloaded.add(fileId));
      case 'dnsHostDiscovered':
        _loadHosts();
    }
  }

  Future<void> _refreshMyApps() async {
    final apps = await ref.read(meshServiceProvider).getLocalApps();
    if (!mounted) return;
    setState(() => _myApps = apps);
  }

  Future<void> _loadHosts() async {
    final info = await ref.read(meshServiceProvider).getLocalNetInfo();
    if (!mounted) return;
    final hosts = ((info?['hosts'] as List?) ?? [])
        .map((e) => Map<String, dynamic>.from(e as Map))
        .where((h) => h['isSelf'] != true && h['hasEndpoint'] == true)
        .map((h) => h['hostname'] as String)
        .toList();
    setState(() {
      _hosts = hosts;
      if (_selectedHost != null && !hosts.contains(_selectedHost)) {
        _selectedHost = null;
        _hostApps = [];
      }
    });
  }

  Future<void> _browseHost(String hostname) async {
    setState(() {
      _selectedHost = hostname;
      _loadingHostApps = true;
      _hostApps = [];
    });
    final apps = await ref.read(meshServiceProvider).getHostApps(hostname);
    if (!mounted) return;
    setState(() {
      _hostApps = apps;
      _loadingHostApps = false;
    });
  }

  Future<void> _downloadApp(Map<String, dynamic> app) async {
    final fileId = app['fileId'] as String;
    final host = _selectedHost;
    if (host == null) return;
    setState(() => _progress[fileId] = 0);
    final started =
        await ref.read(meshServiceProvider).fetchHostFile(host, fileId);
    if (!mounted) return;
    if (!started) {
      setState(() => _progress.remove(fileId));
      _snack('Could not start download.');
    }
    // done/failed arrives via events; Install button appears after 'done'.
  }

  Future<void> _install(Map<String, dynamic> app) async {
    final ok =
        await ref.read(meshServiceProvider).installApk(app['fileId'] as String);
    if (!mounted) return;
    _snack(ok
        ? 'Handed to Android installer — confirm to install.'
        : 'Install failed. Is the APK still available?');
  }

  void _snack(String message) {
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }

  String _fmtSize(dynamic bytes) {
    final b = (bytes as num?)?.toDouble() ?? 0;
    if (b >= 1024 * 1024) return '${(b / 1024 / 1024).toStringAsFixed(1)} MB';
    if (b >= 1024) return '${(b / 1024).toStringAsFixed(0)} KB';
    return '${b.toInt()} B';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: MeshAppTheme.bgDeep,
      appBar: AppBar(
        backgroundColor: MeshAppTheme.bgDeep,
        surfaceTintColor: Colors.transparent,
        title: const Text('App Store',
            style: TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            onPressed: () {
              _refreshMyApps();
              _loadHosts();
              if (_selectedHost != null) _browseHost(_selectedHost!);
            },
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _sectionHeader(Icons.apps_rounded, MeshAppTheme.primary,
              'My Apps (${_myApps.length})'),
          const SizedBox(height: 12),
          if (_myApps.isEmpty)
            _emptyCard(
              Icons.archive_outlined,
              'No APKs shared yet.\nShare an .apk file from LocalNet and it appears here.',
            )
          else
            ..._myApps.map((a) => _appCard(a, local: true)),
          const SizedBox(height: 24),
          _sectionHeader(Icons.cloud_download_rounded, MeshAppTheme.info,
              'From Hosts'),
          const SizedBox(height: 12),
          if (_hosts.isEmpty)
            _emptyCard(
              Icons.devices_rounded,
              'No reachable hosts yet.\nDiscover peers on the LocalNet tab first.',
            )
          else
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: _hosts.map(_hostChip).toList(),
            ),
          if (_loadingHostApps)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (_selectedHost != null && _hostApps.isEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: _emptyCard(
                Icons.app_blocking_rounded,
                '$_selectedHost.mesh shares no APKs.',
              ),
            )
          else
            ..._hostApps.map((a) => _appCard(a, local: false)),
        ],
      ),
    );
  }

  Widget _sectionHeader(IconData icon, Color color, String title) {
    return Row(
      children: [
        Icon(icon, color: color, size: 20),
        const SizedBox(width: 8),
        Text(title,
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
      ],
    );
  }

  Widget _emptyCard(IconData icon, String message) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: MeshAppTheme.bgCard,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: MeshAppTheme.border),
      ),
      child: Column(
        children: [
          Icon(icon, color: MeshAppTheme.textDim, size: 32),
          const SizedBox(height: 8),
          Text(
            message,
            textAlign: TextAlign.center,
            style: TextStyle(color: MeshAppTheme.textGray, fontSize: 13),
          ),
        ],
      ),
    );
  }

  Widget _hostChip(String hostname) {
    final selected = _selectedHost == hostname;
    return ActionChip(
      avatar: Icon(
        Icons.dns_rounded,
        size: 16,
        color: selected ? MeshAppTheme.bgDeep : MeshAppTheme.info,
      ),
      label: Text('$hostname.mesh'),
      backgroundColor: selected ? MeshAppTheme.primary : MeshAppTheme.bgCard,
      labelStyle: TextStyle(
        color: selected ? MeshAppTheme.bgDeep : MeshAppTheme.textWhite,
        fontWeight: FontWeight.w600,
      ),
      side: BorderSide(color: selected ? MeshAppTheme.primary : MeshAppTheme.border),
      onPressed: () => _browseHost(hostname),
    );
  }

  Widget _appCard(Map<String, dynamic> app, {required bool local}) {
    final fileId = app['fileId'] as String;
    final p = _progress[fileId];
    final canInstall = local || _downloaded.contains(fileId);
    final pkg = app['packageName'] as String?;
    final version = app['versionName'] as String?;

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: MeshAppTheme.bgCard,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: canInstall ? MeshAppTheme.success : MeshAppTheme.border,
          ),
        ),
        child: Column(
          children: [
            Row(
              children: [
                Icon(
                  Icons.android_rounded,
                  color: canInstall ? MeshAppTheme.success : MeshAppTheme.textDim,
                  size: 24,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(app['fileName'] as String? ?? '?',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontWeight: FontWeight.w600, fontSize: 14)),
                      const SizedBox(height: 2),
                      Text(
                        pkg != null
                            ? '$pkg${version != null ? ' · v$version' : ''} · ${_fmtSize(app['fileSize'])}'
                            : '${_fmtSize(app['fileSize'])} · package info after download',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style:
                            TextStyle(color: MeshAppTheme.textGray, fontSize: 12),
                      ),
                    ],
                  ),
                ),
                if (p != null)
                  const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else if (canInstall)
                  IconButton(
                    icon: const Icon(Icons.install_mobile_rounded),
                    color: MeshAppTheme.success,
                    tooltip: 'Install',
                    onPressed: () => _install(app),
                  )
                else
                  IconButton(
                    icon: const Icon(Icons.download_rounded),
                    color: MeshAppTheme.info,
                    tooltip: 'Download',
                    onPressed: () => _downloadApp(app),
                  ),
              ],
            ),
            if (p != null) ...[
              const SizedBox(height: 8),
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: LinearProgressIndicator(
                  value: p,
                  minHeight: 5,
                  backgroundColor: MeshAppTheme.bgInput,
                  valueColor: AlwaysStoppedAnimation(MeshAppTheme.primary),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

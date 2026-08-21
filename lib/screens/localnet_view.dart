import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/mesh_service.dart';
import '../theme/app_theme.dart';
import 'admin_view.dart';
import 'app_store_view.dart';
import 'collab_view.dart';
import 'emergency_view.dart';
import 'host_files_page.dart';
import 'internet_view.dart';
import 'search_view.dart';

/// LocalNet (Phase 1) — offline local area networking platform.
///
/// Shows:
///  - this device's .mesh hostname and HTTP server status
///  - all hosts learned through decentralized mesh DNS
class LocalNetView extends ConsumerStatefulWidget {
  const LocalNetView({super.key});

  @override
  ConsumerState<LocalNetView> createState() => _LocalNetViewState();
}

class _LocalNetViewState extends ConsumerState<LocalNetView> {
  Map<String, dynamic>? _info;
  List<Map<String, dynamic>> _sharedFiles = [];
  bool _loading = true;
  StreamSubscription<Map<String, dynamic>>? _sub;

  @override
  void initState() {
    super.initState();
    _refresh();
    _sub = ref.read(meshServiceProvider).events.listen((event) {
      final type = event['event'];
      if (type == 'dnsHostDiscovered' ||
          type == 'dnsHostResolved' ||
          type == 'httpServerState') {
        _refresh();
        if (type == 'dnsHostDiscovered' && mounted) {
          ScaffoldMessenger.of(context).hideCurrentSnackBar();
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('New host: ${event['fqdn']}'),
              behavior: SnackBarBehavior.floating,
              duration: const Duration(seconds: 2),
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12)),
            ),
          );
        }
      }
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final service = ref.read(meshServiceProvider);
    final info = await service.getLocalNetInfo();
    final files = await service.getSharedFiles();
    if (!mounted) return;
    setState(() {
      _info = info;
      _sharedFiles = files;
      _loading = false;
    });
  }

  Future<void> _pickAndShare() async {
    final result = await FilePicker.platform.pickFiles(withData: false);
    if (result == null || result.files.single.path == null) return;
    final manifest =
        await ref.read(meshServiceProvider).shareLocalFile(result.files.single.path!);
    if (!mounted) return;
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(manifest != null
            ? 'Shared: ${manifest['name']} (${manifest['chunkCount']} chunks)'
            : 'Could not share that file.'),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
    _refresh();
  }

  Future<void> _unshare(Map<String, dynamic> file) async {
    final ok =
        await ref.read(meshServiceProvider).unshareFile(file['fileId'] as String);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(ok ? 'Stopped sharing ${file['name']}' : 'Failed.'),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
    _refresh();
  }

  Future<void> _resolve(String hostname) async {
    final result = await ref.read(meshServiceProvider).resolveHost(hostname);
    if (!mounted) return;
    final found = result?['found'] == true;
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(found
            ? '$hostname.mesh -> ${result?['deviceId']}'
            : 'Searching for $hostname.mesh on the mesh...'),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
    await Future.delayed(const Duration(seconds: 3));
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final hostname = _info?['hostname'] as String? ?? '';
    final port = _info?['httpPort'] as int? ?? -1;
    final hosts = (_info?['hosts'] as List?)
            ?.map((e) => Map<String, dynamic>.from(e as Map))
            .toList() ??
        [];

    return Scaffold(
      backgroundColor: MeshAppTheme.bgDeep,
      appBar: AppBar(
        backgroundColor: MeshAppTheme.bgDeep,
        surfaceTintColor: Colors.transparent,
        title: const Text('LocalNet',
            style: TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            onPressed: _refresh,
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
                  // ---- Self card ----
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: MeshAppTheme.bgCard,
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: MeshAppTheme.border),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(Icons.dns_rounded,
                                color: MeshAppTheme.primary, size: 20),
                            const SizedBox(width: 8),
                            const Text('This Device',
                                style: TextStyle(
                                    fontWeight: FontWeight.w700,
                                    fontSize: 15)),
                          ],
                        ),
                        const SizedBox(height: 12),
                        _row('Hostname',
                            hostname.isEmpty ? '-' : '$hostname.mesh'),
                        _row('HTTP Server',
                            port > 0 ? 'Running :$port' : 'Offline'),
                        if (port > 0)
                          Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: Text(
                              'Peers in the Wi-Fi Direct group can open '
                              'http://<your-ip>:$port with any browser.',
                              style: TextStyle(
                                  fontSize: 12,
                                  color: MeshAppTheme.textGray),
                            ),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                  // ---- Collaboration entry ----
                  InkWell(
                    onTap: () {
                      Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => const CollabView(),
                      ));
                    },
                    borderRadius: BorderRadius.circular(16),
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [
                            MeshAppTheme.bgCard,
                            MeshAppTheme.bgElevated,
                          ],
                        ),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: MeshAppTheme.borderLight),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.groups_rounded, color: MeshAppTheme.primary, size: 26),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Collaboration',
                                    style: TextStyle(
                                        fontWeight: FontWeight.w700, fontSize: 15)),
                                SizedBox(height: 2),
                                Text('Shared whiteboard, notes and polls — live on the mesh.',
                                    style: TextStyle(
                                        color: MeshAppTheme.textGray, fontSize: 12)),
                              ],
                            ),
                          ),
                          Icon(Icons.chevron_right_rounded,
                              color: MeshAppTheme.textDim),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  // ---- App Store entry ----
                  InkWell(
                    onTap: () {
                      Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => const AppStoreView(),
                      ));
                    },
                    borderRadius: BorderRadius.circular(16),
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [
                            MeshAppTheme.bgCard,
                            MeshAppTheme.bgElevated,
                          ],
                        ),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: MeshAppTheme.borderLight),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.shopify_rounded,
                              color: MeshAppTheme.success, size: 26),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('App Store',
                                    style: TextStyle(
                                        fontWeight: FontWeight.w700,
                                        fontSize: 15)),
                                SizedBox(height: 2),
                                Text(
                                    'Share and install apps over the mesh — no internet needed.',
                                    style: TextStyle(
                                        color: MeshAppTheme.textGray,
                                        fontSize: 12)),
                              ],
                            ),
                          ),
                          Icon(Icons.chevron_right_rounded,
                              color: MeshAppTheme.textDim),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  // ---- Internet Gateway entry ----
                  InkWell(
                    onTap: () {
                      Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => const InternetGatewayView(),
                      ));
                    },
                    borderRadius: BorderRadius.circular(16),
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [
                            MeshAppTheme.bgCard,
                            MeshAppTheme.bgElevated,
                          ],
                        ),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: MeshAppTheme.borderLight),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.wifi_tethering_rounded,
                              color: MeshAppTheme.info, size: 26),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Internet Gateway',
                                    style: TextStyle(
                                        fontWeight: FontWeight.w700,
                                        fontSize: 15)),
                                SizedBox(height: 2),
                                Text(
                                    'Share your internet via CONNECT/HTTP proxy on the mesh.',
                                    style: TextStyle(
                                        color: MeshAppTheme.textGray,
                                        fontSize: 12)),
                              ],
                            ),
                          ),
                          Icon(Icons.chevron_right_rounded,
                              color: MeshAppTheme.textDim),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  // ---- Emergency Broadcast entry ----
                  InkWell(
                    onTap: () {
                      Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => const EmergencyView(),
                      ));
                    },
                    borderRadius: BorderRadius.circular(16),
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [
                            MeshAppTheme.bgCard,
                            MeshAppTheme.bgElevated,
                          ],
                        ),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: MeshAppTheme.borderLight),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.warning_amber_rounded,
                              color: MeshAppTheme.error, size: 26),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Emergency Broadcast',
                                    style: TextStyle(
                                        fontWeight: FontWeight.w700,
                                        fontSize: 15)),
                                SizedBox(height: 2),
                                Text(
                                    'Send priority alerts that flood the mesh instantly.',
                                    style: TextStyle(
                                        color: MeshAppTheme.textGray,
                                        fontSize: 12)),
                              ],
                            ),
                          ),
                          Icon(Icons.chevron_right_rounded,
                              color: MeshAppTheme.textDim),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  // ---- Mesh Search entry ----
                  InkWell(
                    onTap: () {
                      Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => const SearchView(),
                      ));
                    },
                    borderRadius: BorderRadius.circular(16),
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [
                            MeshAppTheme.bgCard,
                            MeshAppTheme.bgElevated,
                          ],
                        ),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: MeshAppTheme.borderLight),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.search_rounded,
                              color: MeshAppTheme.info, size: 26),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Mesh Search',
                                    style: TextStyle(
                                        fontWeight: FontWeight.w700,
                                        fontSize: 15)),
                                SizedBox(height: 2),
                                Text(
                                    'Search files, boards, docs, polls across the mesh.',
                                    style: TextStyle(
                                        color: MeshAppTheme.textGray,
                                        fontSize: 12)),
                              ],
                            ),
                          ),
                          Icon(Icons.chevron_right_rounded,
                              color: MeshAppTheme.textDim),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  // ---- Admin & RBAC entry ----
                  InkWell(
                    onTap: () {
                      Navigator.of(context).push(MaterialPageRoute(
                        builder: (_) => const AdminView(),
                      ));
                    },
                    borderRadius: BorderRadius.circular(16),
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          colors: [
                            MeshAppTheme.bgCard,
                            MeshAppTheme.bgElevated,
                          ],
                        ),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: MeshAppTheme.borderLight),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.security_rounded,
                              color: MeshAppTheme.success, size: 26),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Admin & RBAC',
                                    style: TextStyle(
                                        fontWeight: FontWeight.w700,
                                        fontSize: 15)),
                                SizedBox(height: 2),
                                Text(
                                    'Roles, permissions, bans, access control.',
                                    style: TextStyle(
                                        color: MeshAppTheme.textGray,
                                        fontSize: 12)),
                              ],
                            ),
                          ),
                          Icon(Icons.chevron_right_rounded,
                              color: MeshAppTheme.textDim),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  // ---- My Files header ----
                  Row(
                    children: [
                      Icon(Icons.folder_shared_rounded,
                          color: MeshAppTheme.warning, size: 20),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text('My Shared Files (${_sharedFiles.length})',
                            style: const TextStyle(
                                fontWeight: FontWeight.w700, fontSize: 15)),
                      ),
                      IconButton(
                        icon: Icon(Icons.add_circle_rounded,
                            color: MeshAppTheme.primary),
                        tooltip: 'Share a file',
                        onPressed: _pickAndShare,
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  if (_sharedFiles.isEmpty)
                    Container(
                      padding: const EdgeInsets.all(24),
                      decoration: BoxDecoration(
                        color: MeshAppTheme.bgCard,
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: MeshAppTheme.border),
                      ),
                      child: Column(
                        children: [
                          Icon(Icons.upload_file_rounded,
                              color: MeshAppTheme.textDim, size: 32),
                          const SizedBox(height: 8),
                          Text(
                            'No files shared yet.\n'
                            'Tap + to share a file with nearby LocalNet peers.',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                                color: MeshAppTheme.textGray, fontSize: 13),
                          ),
                        ],
                      ),
                    )
                  else
                    ..._sharedFiles.map(_fileCard),
                  const SizedBox(height: 20),
                  // ---- Hosts header ----
                  Row(
                    children: [
                      Icon(Icons.lan_rounded,
                          color: MeshAppTheme.info, size: 20),
                      const SizedBox(width: 8),
                      Text('Mesh Hosts (${hosts.length})',
                          style: const TextStyle(
                              fontWeight: FontWeight.w700, fontSize: 15)),
                    ],
                  ),
                  const SizedBox(height: 12),
                  if (hosts.isEmpty)
                    Container(
                      padding: const EdgeInsets.all(24),
                      decoration: BoxDecoration(
                        color: MeshAppTheme.bgCard,
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: MeshAppTheme.border),
                      ),
                      child: Column(
                        children: [
                          Icon(Icons.cloud_off_rounded,
                              color: MeshAppTheme.textDim, size: 32),
                          const SizedBox(height: 8),
                          Text(
                            'No hosts discovered yet.\n'
                            'Nearby LocalNet devices appear here automatically.',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                                color: MeshAppTheme.textGray, fontSize: 13),
                          ),
                        ],
                      ),
                    )
                  else
                    ...hosts.map(_hostCard),
                ],
              ),
            ),
    );
  }

  Widget _fileCard(Map<String, dynamic> f) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: MeshAppTheme.bgCard,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: MeshAppTheme.border),
        ),
        child: Row(
          children: [
            Icon(Icons.insert_drive_file_rounded,
                color: MeshAppTheme.warning, size: 22),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(f['name'] as String? ?? '?',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, fontSize: 14)),
                  const SizedBox(height: 2),
                  Text('${f['chunkCount']} chunks',
                      style:
                          TextStyle(color: MeshAppTheme.textGray, fontSize: 12)),
                ],
              ),
            ),
            IconButton(
              icon: const Icon(Icons.link_off_rounded),
              color: MeshAppTheme.error,
              tooltip: 'Stop sharing',
              onPressed: () => _unshare(f),
            ),
          ],
        ),
      ),
    );
  }

  Widget _hostCard(Map<String, dynamic> host) {
    final isSelf = host['isSelf'] == true;
    final hasEndpoint = host['hasEndpoint'] == true;
    final displayName = (host['displayName'] as String?)?.trim() ?? '';
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: InkWell(
        onTap: isSelf ? null : () => _resolve(host['hostname'] as String),
        borderRadius: BorderRadius.circular(14),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: MeshAppTheme.bgCard,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
                color: isSelf ? MeshAppTheme.primary : MeshAppTheme.border),
          ),
          child: Row(
            children: [
              Icon(
                isSelf ? Icons.home_rounded : Icons.devices_rounded,
                color: isSelf ? MeshAppTheme.primary : MeshAppTheme.success,
                size: 22,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${host['fqdn']}${isSelf ? '  (you)' : ''}',
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, fontSize: 14),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      displayName.isNotEmpty
                          ? displayName
                          : (host['deviceId'] as String? ?? ''),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          color: MeshAppTheme.textGray, fontSize: 12),
                    ),
                  ],
                ),
              ),
              if (hasEndpoint && !isSelf)
                IconButton(
                  icon: Icon(Icons.folder_open_rounded,
                      color: MeshAppTheme.info),
                  tooltip: 'Browse files',
                  onPressed: () {
                    Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) =>
                          HostFilesPage(fqdn: host['fqdn'] as String),
                    ));
                  },
                )
              else if (!isSelf)
                Icon(Icons.travel_explore_rounded,
                    color: MeshAppTheme.textDim, size: 18),
            ],
          ),
        ),
      ),
    );
  }

  Widget _row(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: TextStyle(color: MeshAppTheme.textGray, fontSize: 13)),
          Text(value,
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }
}

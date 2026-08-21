import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/mesh_service.dart';
import '../theme/app_theme.dart';

/// Browse and download files shared by a remote LocalNet host.
class HostFilesPage extends ConsumerStatefulWidget {
  const HostFilesPage({super.key, required this.fqdn});

  final String fqdn;

  @override
  ConsumerState<HostFilesPage> createState() => _HostFilesPageState();
}

class _HostFilesPageState extends ConsumerState<HostFilesPage> {
  List<Map<String, dynamic>> _files = [];
  bool _loading = true;
  String? _error;
  final Map<String, double> _progress = {}; // fileId -> 0..1
  StreamSubscription<Map<String, dynamic>>? _sub;

  String get _hostname =>
      widget.fqdn.endsWith('.mesh')
          ? widget.fqdn.substring(0, widget.fqdn.length - 5)
          : widget.fqdn;

  @override
  void initState() {
    super.initState();
    _load();
    _sub = ref.read(meshServiceProvider).events.listen((event) {
      if (event['event'] != 'fileSyncProgress') return;
      final fileId = event['fileId'] as String?;
      if (fileId == null || !mounted) return;
      final state = event['state'] as String?;
      if (state == 'done' || state == 'failed') {
        setState(() => _progress.remove(fileId));
        ScaffoldMessenger.of(context).hideCurrentSnackBar();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(state == 'done'
                ? 'Saved: ${event['fileName']}'
                : 'Download failed: ${event['fileName']}'),
            behavior: SnackBarBehavior.floating,
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12)),
          ),
        );
        return;
      }
      setState(() {
        _progress[fileId] = (event['total'] as int? ?? 0) > 0
            ? (event['have'] as int? ?? 0) / (event['total'] as int)
            : 0;
      });
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final files =
        await ref.read(meshServiceProvider).getHostFiles(_hostname);
    if (!mounted) return;
    setState(() {
      _files = files;
      _loading = false;
      if (files.isEmpty) _error = 'No shared files found on this host.';
    });
  }

  Future<void> _download(Map<String, dynamic> file) async {
    final fileId = file['fileId'] as String;
    final name = file['name'] as String? ?? 'file';
    setState(() => _progress[fileId] = 0);
    ScaffoldMessenger.of(context).hideCurrentSnackBar();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Downloading $name...'),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
        shape:
            RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
    final started =
        await ref.read(meshServiceProvider).fetchHostFile(_hostname, fileId);
    if (!mounted) return;
    if (!started) {
      setState(() => _progress.remove(fileId));
      ScaffoldMessenger.of(context).hideCurrentSnackBar();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Could not start download of $name.'),
          behavior: SnackBarBehavior.floating,
          shape:
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      );
    }
    // Completion (done/failed) arrives via fileSyncProgress events below.
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
        title: Text(widget.fqdn,
            style: const TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          IconButton(icon: const Icon(Icons.refresh_rounded), onPressed: _load),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _files.isEmpty
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.folder_off_rounded,
                          color: MeshAppTheme.textDim, size: 40),
                      const SizedBox(height: 8),
                      Text(_error ?? 'Nothing here.',
                          style: TextStyle(
                              color: MeshAppTheme.textGray, fontSize: 13)),
                    ],
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.all(16),
                  itemCount: _files.length,
                  itemBuilder: (context, i) {
                    final f = _files[i];
                    final p = _progress[f['fileId']];
                    return Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: Container(
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: MeshAppTheme.bgCard,
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(color: MeshAppTheme.border),
                        ),
                        child: Column(
                          children: [
                            Row(
                              children: [
                                Icon(_iconFor(f['mime'] as String?),
                                    color: MeshAppTheme.info, size: 22),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(f['name'] as String? ?? '?',
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: const TextStyle(
                                              fontWeight: FontWeight.w600,
                                              fontSize: 14)),
                                      const SizedBox(height: 2),
                                      Text(
                                        '${_fmtSize(f['fileSize'])} · ${f['chunkCount']} chunks',
                                        style: TextStyle(
                                            color: MeshAppTheme.textGray,
                                            fontSize: 12),
                                      ),
                                    ],
                                  ),
                                ),
                                IconButton(
                                  icon: p != null
                                      ? const SizedBox(
                                          width: 18,
                                          height: 18,
                                          child: CircularProgressIndicator(
                                              strokeWidth: 2))
                                      : const Icon(Icons.download_rounded),
                                  color: MeshAppTheme.success,
                                  onPressed:
                                      p != null ? null : () => _download(f),
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
                                  valueColor: AlwaysStoppedAnimation(
                                      MeshAppTheme.primary),
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    );
                  },
                ),
    );
  }

  IconData _iconFor(String? mime) {
    if (mime == null) return Icons.insert_drive_file_rounded;
    if (mime.startsWith('image/')) return Icons.image_rounded;
    if (mime.startsWith('video/')) return Icons.movie_rounded;
    if (mime.startsWith('audio/')) return Icons.audiotrack_rounded;
    if (mime.contains('pdf')) return Icons.picture_as_pdf_rounded;
    if (mime.startsWith('text/')) return Icons.description_rounded;
    return Icons.insert_drive_file_rounded;
  }
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:meshnet_app/core/mesh_service.dart';
import 'package:meshnet_app/theme/app_theme.dart';

class SearchView extends ConsumerStatefulWidget {
  const SearchView({super.key});

  @override
  ConsumerState<SearchView> createState() => _SearchViewState();
}

class _SearchViewState extends ConsumerState<SearchView> {
  final _queryCtrl = TextEditingController();
  final _resourceTypes = <String>{'file', 'board', 'doc', 'poll', 'app', 'host'};
  final _selectedTypes = <String>{'file', 'board', 'doc', 'poll', 'app', 'host'};
  List<Map<String, dynamic>> _localResults = [];
  List<Map<String, dynamic>> _distributedResults = [];
  bool _searchingLocal = false;
  bool _searchingDistributed = false;
  String? _distributedQueryId;
  StreamSubscription? _eventSub;

  @override
  void initState() {
    super.initState();
    _subscribe();
  }

  @override
  void dispose() {
    _queryCtrl.dispose();
    _eventSub?.cancel();
    super.dispose();
  }

  void _subscribe() {
    _eventSub = ref.read(meshServiceProvider).events.listen((event) {
      if (event['event'] == 'searchResult' && event['queryId'] == _distributedQueryId) {
        final results = (event['results'] as List?)?.cast<Map<String, dynamic>>() ?? [];
        if (mounted) setState(() => _distributedResults.addAll(results));
      }
    });
  }

  Future<void> _doLocalSearch() async {
    if (_queryCtrl.text.trim().isEmpty) return;
    setState(() { _searchingLocal = true; _localResults = []; });
    final terms = _queryCtrl.text.trim().split(RegExp(r'\s+'));
    final results = await ref.read(meshServiceProvider).searchLocal(
      terms: terms,
      resourceTypes: _selectedTypes,
      maxResults: 50,
    );
    if (mounted) setState(() { _searchingLocal = false; _localResults = results; });
  }

  Future<void> _doDistributedSearch() async {
    if (_queryCtrl.text.trim().isEmpty) return;
    setState(() { _searchingDistributed = true; _distributedResults = []; });
    final terms = _queryCtrl.text.trim().split(RegExp(r'\s+'));
    final queryId = await ref.read(meshServiceProvider).searchDistributed(
      terms: terms,
      resourceTypes: _selectedTypes,
      maxResults: 50,
    );
    if (mounted) setState(() => _distributedQueryId = queryId);
    // Results come via event stream; timeout after 10s
    await Future.delayed(const Duration(seconds: 10));
    if (mounted) setState(() => _searchingDistributed = false);
  }

  void _toggleType(String type) {
    setState(() {
      if (_selectedTypes.contains(type)) _selectedTypes.remove(type);
      else _selectedTypes.add(type);
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Mesh Search'),
        actions: [
          IconButton(icon: const Icon(Icons.tune), onPressed: _showTypeFilter),
        ],
      ),
      body: Column(
        children: [
          // Search Bar
          Card(
            margin: const EdgeInsets.all(16),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextField(
                    controller: _queryCtrl,
                    decoration: InputDecoration(
                      labelText: 'Search terms',
                      hintText: 'e.g. "meeting notes" or "photo.jpg"',
                      border: const OutlineInputBorder(),
                      suffixIcon: _queryCtrl.text.isNotEmpty
                          ? IconButton(icon: const Icon(Icons.clear), onPressed: () { _queryCtrl.clear(); setState(() {}); })
                          : null,
                    ),
                    onSubmitted: (_) => _doLocalSearch(),
                    onChanged: (_) => setState(() {}),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    children: _resourceTypes.map((t) => FilterChip(
                      label: Text(t),
                      selected: _selectedTypes.contains(t),
                      onSelected: (_) => _toggleType(t),
                    )).toList(),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(child: FilledButton.icon(
                        icon: _searchingLocal ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.search),
                        label: Text(_searchingLocal ? 'Searching...' : 'Search Local'),
                        onPressed: _queryCtrl.text.trim().isEmpty || _searchingLocal ? null : _doLocalSearch,
                      )),
                      const SizedBox(width: 12),
                      Expanded(child: FilledButton.tonalIcon(
                        icon: _searchingDistributed ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.cloud),
                        label: Text(_searchingDistributed ? 'Searching Mesh...' : 'Search Mesh'),
                        onPressed: _queryCtrl.text.trim().isEmpty || _searchingDistributed ? null : _doDistributedSearch,
                      )),
                    ],
                  ),
                ],
              ),
            ),
          ),

          // Results
          Expanded(
            child: DefaultTabController(
              length: 2,
              child: Column(
                children: [
                  const TabBar(
                    tabs: [
                      Tab(text: 'Local Index'),
                      Tab(text: 'Mesh Results'),
                    ],
                  ),
                  Expanded(
                    child: TabBarView(
                      children: [
                        _ResultsList(
                          results: _localResults,
                          searching: _searchingLocal,
                          emptyMessage: 'No local matches. Try searching the mesh.',
                        ),
                        _ResultsList(
                          results: _distributedResults,
                          searching: _searchingDistributed,
                          emptyMessage: 'No mesh results yet. Waiting for peers...',
                        ),
                      ],
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

  void _showTypeFilter() {
    showModalBottomSheet(
      context: context,
      builder: (context) => Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Resource Types', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 12),
            Wrap(spacing: 8, children: _resourceTypes.map((t) => FilterChip(
              label: Text(t), selected: _selectedTypes.contains(t),
              onSelected: (_) => { _toggleType(t), Navigator.pop(context) },
            )).toList()),
          ],
        ),
      ),
    );
  }
}

class _ResultsList extends StatelessWidget {
  final List<Map<String, dynamic>> results;
  final bool searching;
  final String emptyMessage;

  const _ResultsList({required this.results, required this.searching, required this.emptyMessage});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (searching && results.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (results.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.search_off_rounded, size: 64, color: theme.colorScheme.onSurfaceVariant),
            const SizedBox(height: 16),
            Text(emptyMessage, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant), textAlign: TextAlign.center),
          ],
        ),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: results.length,
      itemBuilder: (context, index) => _ResultCard(result: results[index]),
    );
  }
}

class _ResultCard extends StatelessWidget {
  final Map<String, dynamic> result;

  const _ResultCard({required this.result});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final resourceType = result['resourceType'] as String? ?? 'unknown';
    final icon = _iconForType(resourceType);
    final color = _colorForType(resourceType, theme);

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      elevation: 0,
      color: theme.colorScheme.surfaceContainerHighest,
      child: ListTile(
        leading: CircleAvatar(backgroundColor: color.withValues(alpha: 0.2), child: Icon(icon, color: color)),
        title: Text(result['title'] ?? 'Untitled', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600)),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 4),
            Text(result['snippet'] ?? '', maxLines: 2, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall),
            const SizedBox(height: 4),
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(color: theme.colorScheme.primaryContainer, borderRadius: BorderRadius.circular(4)),
                  child: Text(resourceType, style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onPrimaryContainer)),
                ),
                const SizedBox(width: 8),
                if (result['score'] != null)
                  Text('Score: ${(result['score'] as num).toStringAsFixed(1)}', style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
              ],
            ),
          ],
        ),
        trailing: Icon(Icons.chevron_right_rounded, color: theme.colorScheme.onSurfaceVariant),
        onTap: () {
          // TODO: Open resource detail
        },
      ),
    );
  }

  IconData _iconForType(String type) {
    switch (type) {
      case 'file': return Icons.insert_drive_file_rounded;
      case 'board': return Icons.gesture_rounded;
      case 'doc': return Icons.description_rounded;
      case 'poll': return Icons.poll_rounded;
      case 'app': return Icons.android_rounded;
      case 'host': return Icons.dns_rounded;
      default: return Icons.help_outline_rounded;
    }
  }

  Color _colorForType(String type, ThemeData theme) {
    switch (type) {
      case 'file': return theme.colorScheme.secondary;
      case 'board': return theme.colorScheme.primary;
      case 'doc': return theme.colorScheme.tertiary;
      case 'poll': return Colors.green;
      case 'app': return theme.colorScheme.tertiary;
      case 'host': return Colors.blue;
      default: return theme.colorScheme.onSurfaceVariant;
    }
  }
}
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/mesh_service.dart';
import '../theme/app_theme.dart';

/// LocalNet Collaboration (Phase 3): shared whiteboard, notes and polls
/// synced over the mesh in real time.
class CollabView extends ConsumerStatefulWidget {
  const CollabView({super.key});

  @override
  ConsumerState<CollabView> createState() => _CollabViewState();
}

class _CollabViewState extends ConsumerState<CollabView>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 3, vsync: this);
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: MeshAppTheme.bgDeep,
      appBar: AppBar(
        backgroundColor: MeshAppTheme.bgDeep,
        surfaceTintColor: Colors.transparent,
        title:
            const Text('Collaboration', style: TextStyle(fontWeight: FontWeight.w700)),
        bottom: TabBar(
          controller: _tabs,
          indicatorColor: MeshAppTheme.primary,
          labelColor: MeshAppTheme.textWhite,
          unselectedLabelColor: MeshAppTheme.textGray,
          tabs: const [
            Tab(icon: Icon(Icons.draw_rounded), text: 'Board'),
            Tab(icon: Icon(Icons.notes_rounded), text: 'Notes'),
            Tab(icon: Icon(Icons.poll_rounded), text: 'Polls'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabs,
        children: const [_BoardTab(), _NotesTab(), _PollsTab()],
      ),
    );
  }
}

// =====================================================================
// Whiteboard
// =====================================================================

class _BoardTab extends ConsumerStatefulWidget {
  const _BoardTab();

  @override
  ConsumerState<_BoardTab> createState() => _BoardTabState();
}

class _BoardTabState extends ConsumerState<_BoardTab> {
  static const List<Color> _palette = [
    Color(0xFFF0F4F8), // white
    Color(0xFF4E8CFF), // blue
    Color(0xFF3DDC84), // green
    Color(0xFFFFB74D), // amber
    Color(0xFFFF5252), // red
  ];

  final List<StrokeData> _strokes = [];
  List<Offset> _livePoints = [];
  Color _color = _palette[1];
  static const double _width = 3;
  String? _roomId;
  bool _loading = true;
  StreamSubscription<Map<String, dynamic>>? _sub;

  @override
  void initState() {
    super.initState();
    _openBoard();
    _sub = ref.read(meshServiceProvider).events.listen((event) {
      if (event['event'] == 'collabStroke' && mounted) {
        final stroke = StrokeData.fromMap(event);
        if (stroke != null) {
          setState(() => _strokes.add(stroke));
        }
      } else if (event['event'] == 'collabBoardCleared' &&
          event['roomId'] == _roomId &&
          mounted) {
        setState(() => _strokes.clear());
      }
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _openBoard() async {
    final service = ref.read(meshServiceProvider);
    final info = await service.createBoard('main-board');
    if (!mounted) return;
    setState(() => _roomId = info?['roomId'] as String? ?? 'main-board');
    final board = await service.getBoard(_roomId!);
    if (!mounted) return;
    final strokes = (board?['strokes'] as List?)
            ?.map((s) =>
                StrokeData.fromMap(Map<String, dynamic>.from(s as Map)))
            .whereType<StrokeData>()
            .toList() ??
        [];
    setState(() {
      _strokes
        ..clear()
        ..addAll(strokes);
      _loading = false;
    });
  }

  Future<void> _onPanEnd() async {
    if (_livePoints.length < 2 || _roomId == null) return;
    final pts = _livePoints
        .map((p) => [double.parse(p.dx.toStringAsFixed(1)), double.parse(p.dy.toStringAsFixed(1))])
        .toList();
    final local = List<StrokeData>.from(_strokes)
      ..add(StrokeData(
        strokeId: 'local',
        color: _color.toARGB32(),
        width: _width,
        points: pts.map((p) => Offset(p[0], p[1])).toList(),
      ));
    setState(() {
      _strokes.clear();
      _strokes.addAll(local);
      _livePoints = [];
    });
    await ref.read(meshServiceProvider).sendStroke(_roomId!, _color.toARGB32(), _width, pts);
  }

  Future<void> _clear() async {
    if (_roomId == null) return;
    final ok = await ref.read(meshServiceProvider).clearBoard(_roomId!);
    if (ok && mounted) setState(() => _strokes.clear());
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    return Column(
      children: [
        // Toolbar
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(
            children: [
              ..._palette.map((c) => GestureDetector(
                    onTap: () => setState(() => _color = c),
                    child: Container(
                      margin: const EdgeInsets.only(right: 8),
                      width: 28,
                      height: 28,
                      decoration: BoxDecoration(
                        color: c,
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: _color == c ? MeshAppTheme.textWhite : Colors.transparent,
                          width: 2.5,
                        ),
                      ),
                    ),
                  )),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.delete_sweep_rounded),
                color: MeshAppTheme.error,
                tooltip: 'Clear board (everyone)',
                onPressed: _clear,
              ),
            ],
          ),
        ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            child: GestureDetector(
              onPanStart: (d) => setState(() => _livePoints = [d.localPosition]),
              onPanUpdate: (d) =>
                  setState(() => _livePoints = [..._livePoints, d.localPosition]),
              onPanEnd: (_) => _onPanEnd(),
              child: CustomPaint(
                painter: _BoardPainter(strokes: _strokes, livePoints: _livePoints, liveColor: _color, liveWidth: _width),
                child: Container(
                  decoration: BoxDecoration(
                    color: MeshAppTheme.bgSurface,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: MeshAppTheme.border),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class StrokeData {
  final String strokeId;
  final int color;
  final double width;
  final List<Offset> points;

  StrokeData({
    required this.strokeId,
    required this.color,
    required this.width,
    required this.points,
  });

  static StrokeData? fromMap(Map<String, dynamic> m) {
    try {
      final raw = m['points'];
      if (raw is! List) return null;
      return StrokeData(
        strokeId: m['strokeId'] as String? ?? '?',
        color: (m['color'] as num?)?.toInt() ?? 0xFFFFFFFF,
        width: (m['width'] as num?)?.toDouble() ?? 3,
        points: raw
            .map((p) => (p as List).map((v) => (v as num).toDouble()).toList())
            .map((xy) => Offset(xy[0], xy[1]))
            .toList(),
      );
    } catch (_) {
      return null;
    }
  }
}

class _BoardPainter extends CustomPainter {
  _BoardPainter({
    required this.strokes,
    required this.livePoints,
    required this.liveColor,
    required this.liveWidth,
  });

  final List<StrokeData> strokes;
  final List<Offset> livePoints;
  final Color liveColor;
  final double liveWidth;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.clipRect(Offset.zero & size);
    for (final s in [...strokes, if (livePoints.length >= 2) StrokeData(strokeId: '', color: liveColor.toARGB32(), width: liveWidth, points: livePoints)]) {
      if (s.points.length < 2) continue;
      final paint = Paint()
        ..color = Color(s.color)
        ..strokeWidth = s.width
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round
        ..style = PaintingStyle.stroke;
      final path = Path()..moveTo(s.points.first.dx, s.points.first.dy);
      for (final p in s.points.skip(1)) {
        path.lineTo(p.dx, p.dy);
      }
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _BoardPainter old) => true;
}

// =====================================================================
// Shared notes
// =====================================================================

class _NotesTab extends ConsumerStatefulWidget {
  const _NotesTab();

  @override
  ConsumerState<_NotesTab> createState() => _NotesTabState();
}

class _NotesTabState extends ConsumerState<_NotesTab> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focus = FocusNode();
  String? _docId;
  int _rev = 0;
  bool _dirty = false; // local unsaved edits
  Timer? _saveDebounce;
  StreamSubscription<Map<String, dynamic>>? _sub;

  @override
  void initState() {
    super.initState();
    _openDoc();
    _sub = ref.read(meshServiceProvider).events.listen((event) {
      if (event['event'] == 'docUpdated' && event['docId'] == _docId && mounted) {
        // Remote edit arrived: apply unless the user is typing
        if (!_dirty && !_focus.hasFocus) {
          setState(() {
            _rev = event['rev'] as int? ?? _rev;
            _controller.text = event['text'] as String? ?? _controller.text;
          });
        } else {
          ScaffoldMessenger.of(context).hideCurrentSnackBar();
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: const Text('Remote note update received — reload to see it.'),
            behavior: SnackBarBehavior.floating,
            action: SnackBarAction(label: 'Reload', onPressed: _reloadDoc),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          ));
        }
      }
    });
  }

  @override
  void dispose() {
    _saveDebounce?.cancel();
    _sub?.cancel();
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  Future<void> _openDoc() async {
    final service = ref.read(meshServiceProvider);
    final doc = await service.createDoc('team-notes', 'Team Notes');
    if (!mounted || doc == null) return;
    setState(() {
      _docId = doc['docId'] as String?;
      _rev = doc['rev'] as int? ?? 0;
      _controller.text = doc['text'] as String? ?? '';
    });
  }

  Future<void> _reloadDoc() async {
    if (_docId == null) return;
    final doc = await ref.read(meshServiceProvider).getDoc(_docId!);
    if (!mounted || doc == null) return;
    setState(() {
      _rev = doc['rev'] as int? ?? _rev;
      _controller.text = doc['text'] as String? ?? '';
      _dirty = false;
    });
  }

  void _onChanged(String text) {
    _dirty = true;
    _saveDebounce?.cancel();
    _saveDebounce = Timer(const Duration(milliseconds: 800), () async {
      if (_docId == null) return;
      final rev = await ref.read(meshServiceProvider).editDoc(_docId!, text);
      if (rev != null && mounted) {
        setState(() {
          _rev = rev;
          _dirty = false;
        });
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.notes_rounded, color: MeshAppTheme.info, size: 18),
              const SizedBox(width: 8),
              Text('Team Notes · rev $_rev${_dirty ? " •" : ""}',
                  style: TextStyle(color: MeshAppTheme.textGray, fontSize: 13)),
              const Spacer(),
              Text('last-writer-wins sync',
                  style: TextStyle(color: MeshAppTheme.textDim, fontSize: 11)),
            ],
          ),
          const SizedBox(height: 10),
          Expanded(
            child: TextField(
              controller: _controller,
              focusNode: _focus,
              maxLines: null,
              expands: true,
              textAlignVertical: TextAlignVertical.top,
              style: const TextStyle(fontSize: 14, height: 1.5),
              onChanged: _onChanged,
              decoration: InputDecoration(
                hintText: 'Shared notes — everyone on the mesh sees your edits...',
                hintStyle: TextStyle(color: MeshAppTheme.textDim),
                filled: true,
                fillColor: MeshAppTheme.bgCard,
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(16),
                  borderSide: BorderSide(color: MeshAppTheme.border),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(16),
                  borderSide: BorderSide(color: MeshAppTheme.primary),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// =====================================================================
// Polls
// =====================================================================

class _PollsTab extends ConsumerStatefulWidget {
  const _PollsTab();

  @override
  ConsumerState<_PollsTab> createState() => _PollsTabState();
}

class _PollsTabState extends ConsumerState<_PollsTab> {
  List<Map<String, dynamic>> _polls = [];
  bool _loading = true;
  StreamSubscription<Map<String, dynamic>>? _sub;

  @override
  void initState() {
    super.initState();
    _refresh();
    _sub = ref.read(meshServiceProvider).events.listen((event) {
      if (event['event'] == 'pollUpdated' && mounted) _refresh();
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final polls = await ref.read(meshServiceProvider).getPolls();
    if (!mounted) return;
    setState(() {
      _polls = polls;
      _loading = false;
    });
  }

  Future<void> _createPoll() async {
    final questionCtrl = TextEditingController();
    final optionCtrls = [TextEditingController(), TextEditingController()];
    String? question;
    List<String>? options;
    await showModalBottomSheet<bool>(
      context: context,
      backgroundColor: MeshAppTheme.bgCard,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => StatefulBuilder(
        builder: (context, setSheet) => Padding(
          padding: EdgeInsets.fromLTRB(
              20, 20, 20, MediaQuery.of(context).viewInsets.bottom + 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('New poll',
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
              const SizedBox(height: 12),
              TextField(
                controller: questionCtrl,
                decoration: InputDecoration(hintText: 'Question'),
              ),
              const SizedBox(height: 8),
              ...optionCtrls.asMap().entries.map((e) => Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: TextField(
                      controller: e.value,
                      decoration: InputDecoration(hintText: 'Option ${e.key + 1}'),
                    ),
                  )),
              TextButton.icon(
                onPressed: optionCtrls.length >= 10
                    ? null
                    : () => setSheet(() => optionCtrls.add(TextEditingController())),
                icon: const Icon(Icons.add, size: 16),
                label: const Text('Add option'),
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () {
                    final q = questionCtrl.text.trim();
                    final opts =
                        optionCtrls.map((c) => c.text.trim()).where((t) => t.isNotEmpty).toList();
                    if (q.isEmpty || opts.length < 2) return;
                    question = q;
                    options = opts;
                    Navigator.pop(context, true);
                  },
                  child: const Text('Create'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
    questionCtrl.dispose();
    for (final c in optionCtrls) {
      c.dispose();
    }
    if (question == null || options == null || !mounted) return;
    final poll = await ref.read(meshServiceProvider).createPoll(question!, options!);
    if (poll != null && mounted) _refresh();
  }

  Future<void> _vote(String pollId, int index) async {
    final ok = await ref.read(meshServiceProvider).votePoll(pollId, index);
    if (ok && mounted) _refresh();
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    return Stack(
      children: [
        RefreshIndicator(
          onRefresh: _refresh,
          child: _polls.isEmpty
              ? ListView(children: [
                  Padding(
                    padding: const EdgeInsets.all(48),
                    child: Column(
                      children: [
                        Icon(Icons.poll_rounded, color: MeshAppTheme.textDim, size: 40),
                        const SizedBox(height: 8),
                        Text('No polls yet.\nCreate one and flood it to the mesh.',
                            textAlign: TextAlign.center,
                            style: TextStyle(color: MeshAppTheme.textGray, fontSize: 13)),
                      ],
                    ),
                  ),
                ])
              : ListView.builder(
                  padding: const EdgeInsets.all(16),
                  itemCount: _polls.length,
                  itemBuilder: (context, i) {
                    final p = _polls[i];
                    final tally = (p['tally'] as Map).cast<int, int>();
                    final total = p['totalVotes'] as int? ?? 0;
                    final options = (p['options'] as List).cast<String>();
                    return Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Container(
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: MeshAppTheme.bgCard,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: MeshAppTheme.border),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(p['question'] as String? ?? '',
                                style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                            const SizedBox(height: 4),
                            Text('$total votes',
                                style: TextStyle(color: MeshAppTheme.textGray, fontSize: 12)),
                            const SizedBox(height: 10),
                            ...options.asMap().entries.map((e) {
                              final count = tally[e.key] ?? 0;
                              final frac = total > 0 ? count / total : 0.0;
                              return Padding(
                                padding: const EdgeInsets.only(bottom: 8),
                                child: InkWell(
                                  onTap: () => _vote(p['pollId'] as String, e.key),
                                  borderRadius: BorderRadius.circular(10),
                                  child: Container(
                                    padding: const EdgeInsets.all(10),
                                    decoration: BoxDecoration(
                                      color: MeshAppTheme.bgInput,
                                      borderRadius: BorderRadius.circular(10),
                                    ),
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        Row(
                                          children: [
                                            Expanded(
                                                child: Text(e.value,
                                                    style: const TextStyle(fontSize: 13))),
                                            Text('$count',
                                                style: TextStyle(
                                                    color: MeshAppTheme.textGray, fontSize: 12)),
                                          ],
                                        ),
                                        const SizedBox(height: 6),
                                        ClipRRect(
                                          borderRadius: BorderRadius.circular(3),
                                          child: LinearProgressIndicator(
                                            value: frac,
                                            minHeight: 4,
                                            backgroundColor: MeshAppTheme.border,
                                            valueColor: AlwaysStoppedAnimation(MeshAppTheme.primary),
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              );
                            }),
                          ],
                        ),
                      ),
                    );
                  },
                ),
        ),
        Positioned(
          right: 16,
          bottom: 16,
          child: FloatingActionButton.extended(
            heroTag: 'new-poll',
            backgroundColor: MeshAppTheme.primary,
            icon: const Icon(Icons.add_rounded),
            label: const Text('Poll'),
            onPressed: _createPoll,
          ),
        ),
      ],
    );
  }
}

import 'dart:async';
import 'package:flutter/material.dart';

class VoiceRecorderButton extends StatefulWidget {
  final Function(String filePath, Duration duration) onRecordComplete;
  final VoidCallback? onCancel;

  const VoiceRecorderButton({
    super.key,
    required this.onRecordComplete,
    this.onCancel,
  });

  @override
  State<VoiceRecorderButton> createState() => _VoiceRecorderButtonState();
}

class _VoiceRecorderButtonState extends State<VoiceRecorderButton>
    with SingleTickerProviderStateMixin {
  bool _isRecording = false;
  bool _isCancelled = false;
  late AnimationController _animController;
  DateTime? _startTime;
  Timer? _durationTimer;
  Duration _currentDuration = Duration.zero;

  @override
  void initState() {
    super.initState();
    _animController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 500),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _animController.dispose();
    _durationTimer?.cancel();
    super.dispose();
  }

  void _onLongPressStart(LongPressStartDetails details) {
    setState(() {
      _isRecording = true;
      _isCancelled = false;
      _startTime = DateTime.now();
      _currentDuration = Duration.zero;
    });
    _durationTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (_startTime != null) {
        setState(() {
          _currentDuration = DateTime.now().difference(_startTime!);
        });
      }
    });
  }

  void _onLongPressEnd(LongPressEndDetails details) {
    _durationTimer?.cancel();
    if (!_isRecording) return;

    final duration = _currentDuration;
    setState(() {
      _isRecording = false;
    });

    if (_isCancelled || duration.inSeconds < 1) {
      widget.onCancel?.call();
      return;
    }

    widget.onRecordComplete('', duration);
  }

  void _onLongPressMoveUpdate(LongPressMoveUpdateDetails details) {
    final RenderBox? renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox == null) return;
    final localPosition = renderBox.globalToLocal(details.globalPosition);
    if (localPosition.dy < -60) {
      setState(() => _isCancelled = true);
    } else {
      setState(() => _isCancelled = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onLongPressStart: _onLongPressStart,
      onLongPressEnd: _onLongPressEnd,
      onLongPressMoveUpdate: _onLongPressMoveUpdate,
      child: AnimatedBuilder(
        animation: _animController,
        builder: (context, child) {
          final scale = _isRecording ? 1.0 + (_animController.value * 0.2) : 1.0;
          return Transform.scale(
            scale: scale,
            child: Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: _isRecording
                    ? (_isCancelled ? Colors.red : Colors.red.withOpacity(0.8))
                    : const Color(0xFF00897B),
                shape: BoxShape.circle,
                boxShadow: _isRecording
                    ? [
                        BoxShadow(
                          color: (_isCancelled ? Colors.red : Colors.redAccent)
                              .withOpacity(0.4),
                          blurRadius: 12,
                          spreadRadius: 2,
                        )
                      ]
                    : null,
              ),
              child: Icon(
                _isRecording
                    ? (_isCancelled ? Icons.delete : Icons.mic)
                    : Icons.mic,
                color: Colors.white,
                size: 28,
              ),
            ),
          );
        },
      ),
    );
  }
}

import 'package:flutter/material.dart';

class AudioWaveform extends StatelessWidget {
  final List<double> amplitudes;
  final Duration position;
  final Duration totalDuration;
  final Color activeColor;
  final Color inactiveColor;
  final double height;

  const AudioWaveform({
    super.key,
    required this.amplitudes,
    required this.position,
    required this.totalDuration,
    this.activeColor = const Color(0xFF00897B),
    this.inactiveColor = Colors.white30,
    this.height = 40,
  });

  @override
  Widget build(BuildContext context) {
    if (amplitudes.isEmpty) {
      return SizedBox(
        height: height,
        child: Center(
          child: Text(
            '0:00',
            style: TextStyle(color: Colors.white.withOpacity(0.5), fontSize: 12),
          ),
        ),
      );
    }

    final progress = totalDuration.inMilliseconds > 0
        ? position.inMilliseconds / totalDuration.inMilliseconds
        : 0.0;

    return SizedBox(
      height: height,
      child: CustomPaint(
        size: Size.infinite,
        painter: _WaveformPainter(
          amplitudes: amplitudes,
          progress: progress.clamp(0.0, 1.0),
          activeColor: activeColor,
          inactiveColor: inactiveColor,
        ),
      ),
    );
  }
}

class _WaveformPainter extends CustomPainter {
  final List<double> amplitudes;
  final double progress;
  final Color activeColor;
  final Color inactiveColor;

  _WaveformPainter({
    required this.amplitudes,
    required this.progress,
    required this.activeColor,
    required this.inactiveColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (amplitudes.isEmpty) return;

    final barWidth = 3.0;
    final spacing = 2.0;
    final totalBars = amplitudes.length;
    final maxBarHeight = size.height * 0.85;
    final centerY = size.height / 2;

    for (int i = 0; i < totalBars; i++) {
      final x = i * (barWidth + spacing);
      final barHeight = (amplitudes[i] * maxBarHeight).clamp(4.0, maxBarHeight);
      final isActive = (i / totalBars) <= progress;

      final paint = Paint()
        ..color = isActive ? activeColor : inactiveColor
        ..strokeWidth = barWidth
        ..strokeCap = StrokeCap.round;

      canvas.drawLine(
        Offset(x, centerY - barHeight / 2),
        Offset(x, centerY + barHeight / 2),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _WaveformPainter oldDelegate) {
    return oldDelegate.progress != progress || oldDelegate.amplitudes != amplitudes;
  }
}

import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/mesh_service.dart';
import '../models/message_model.dart';
import '../theme/app_theme.dart';

/// Voice message player with waveform visualization and playback speed control
class VoiceMessagePlayer extends ConsumerStatefulWidget {
  final ChatMessage message;
  final VoidCallback? onPlayPause;
  final VoidCallback? onSpeedChange;

  const VoiceMessagePlayer({
    super.key,
    required this.message,
    this.onPlayPause,
    this.onSpeedChange,
  });

  @override
  ConsumerState<VoiceMessagePlayer> createState() => _VoiceMessagePlayerState();
}

class _VoiceMessagePlayerState extends ConsumerState<VoiceMessagePlayer>
    with SingleTickerProviderStateMixin {
  late AnimationController _waveAnimationController;
  final List<double> _waveformData = [];
  Timer? _positionTimer;
  Timer? _waveTimer;
  bool _isGeneratingWaveform = false;

  @override
  void initState() {
    super.initState();
    _waveAnimationController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1000),
    )..repeat();
    _generateWaveform();
    _startPositionTimer();
  }

  @override
  void dispose() {
    _waveAnimationController.dispose();
    _positionTimer?.cancel();
    _waveTimer?.cancel();
    super.dispose();
  }

  Future<void> _generateWaveform() async {
    if (_isGeneratingWaveform) return;
    _isGeneratingWaveform = true;
    
    // Simulate waveform data generation
    // In a real implementation, this would decode the audio file and extract amplitude data
    final newWaveform = List<double>.generate(50, (index) {
      // Generate pseudo-random but deterministic waveform based on message
      final seed = widget.message.messageId.hashCode + index;
      return math.Random(seed).nextDouble() * 0.8 + 0.2;
    });
    
    if (mounted) {
      setState(() {
        _waveformData.clear();
        _waveformData.addAll(newWaveform);
      });
    }
    _isGeneratingWaveform = false;
  }

  void _startPositionTimer() {
    _positionTimer?.cancel();
    if (widget.message.isPlaying) {
      _positionTimer = Timer.periodic(const Duration(milliseconds: 100), (timer) {
        if (!mounted) {
          timer.cancel();
          return;
        }
        final newPosition = widget.message.playbackPosition + 
            0.1 * widget.message.playbackSpeed;
        final duration = widget.message.audioDuration?.inMilliseconds ?? 0;
        
        if (newPosition * 1000 >= duration) {
          timer.cancel();
          // Auto-pause at end
          ref.read(meshServiceProvider).pauseVoiceMessage(widget.message.messageId);
        }
      });
    }
  }

  void _onPlayPause() {
    if (widget.message.isPlaying) {
      ref.read(meshServiceProvider).pauseVoiceMessage(widget.message.messageId);
    } else {
      ref.read(meshServiceProvider).playVoiceMessage(widget.message.messageId);
    }
    widget.onPlayPause?.call();
  }

  void _onSpeedChange() {
    final speeds = [0.5, 1.0, 1.5, 2.0];
    final currentIndex = speeds.indexOf(widget.message.playbackSpeed);
    final nextIndex = (currentIndex + 1) % speeds.length;
    final newSpeed = speeds[nextIndex];
    
    ref.read(meshServiceProvider).setVoicePlaybackSpeed(
      widget.message.messageId, 
      newSpeed
    );
    widget.onSpeedChange?.call();
  }

  @override
  Widget build(BuildContext context) {
    final me = widget.message.fromMe;
    final isVoice = widget.message.isVoiceMessage;
    final duration = widget.message.audioDuration ?? Duration.zero;
    final position = widget.message.playbackPosition * 1000; // in ms
    final durationMs = duration.inMilliseconds;
    final progress = durationMs > 0 ? (position / durationMs).clamp(0.0, 1.0) : 0.0;
    final isPlaying = widget.message.isPlaying;

    if (!isVoice) return const SizedBox.shrink();

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      constraints: BoxConstraints(
        maxWidth: MediaQuery.of(context).size.width * 0.75,
      ),
      decoration: BoxDecoration(
        color: widget.message.fromMe 
            ? MeshAppTheme.sentBubble 
            : MeshAppTheme.receivedBubble,
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(18),
          topRight: const Radius.circular(18),
          bottomLeft: Radius.circular(widget.message.fromMe ? 18 : 4),
          bottomRight: Radius.circular(widget.message.fromMe ? 4 : 18),
        ),
        border: widget.message.fromMe
            ? null
            : Border.all(color: MeshAppTheme.border, width: 1),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          // Waveform visualization
          _WaveformView(
            waveformData: _waveformData,
            progress: progress,
            isPlaying: isPlaying,
            animationController: _waveAnimationController,
            color: me ? Colors.white.withValues(alpha: 0.7) : MeshAppTheme.textGray,
          ),
          
          const SizedBox(height: 8),
          
          // Progress bar with time labels
          Row(
            children: [
              Text(
                widget.message.formattedPosition,
                style: TextStyle(
                  fontSize: 11,
                  color: me 
                      ? Colors.white.withValues(alpha: 0.7) 
                      : MeshAppTheme.textDim,
                  fontFamily: 'monospace',
                ),
              ),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(2),
                    child: LinearProgressIndicator(
                      value: progress,
                      minHeight: 3,
                      backgroundColor: me 
                          ? Colors.white.withValues(alpha: 0.2) 
                          : MeshAppTheme.border,
                      valueColor: AlwaysStoppedAnimation<Color>(
                        me ? Colors.white : MeshAppTheme.primary,
                      ),
                    ),
                  ),
                ),
              ),
              Text(
                widget.message.displayDuration,
                style: TextStyle(
                  fontSize: 11,
                  color: me 
                      ? Colors.white.withValues(alpha: 0.7) 
                      : MeshAppTheme.textDim,
                  fontFamily: 'monospace',
                ),
              ),
            ],
          ),
          
          const SizedBox(height: 8),
          
          // Controls
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              // Playback speed
              _SpeedButton(
                speed: widget.message.playbackSpeed,
                onPressed: _onSpeedChange,
                color: me ? Colors.white : MeshAppTheme.textWhite,
              ),
              const SizedBox(width: 8),
              
              // Play/Pause button
              _PlayPauseButton(
                isPlaying: isPlaying,
                onPressed: _onPlayPause,
                color: me ? Colors.white : MeshAppTheme.textWhite,
              ),
              
              if (widget.message.fromMe) ...[
                const SizedBox(width: 8),
                _StatusIcon(status: widget.message.status),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

/// Waveform visualization widget
class _WaveformView extends StatelessWidget {
  final List<double> waveformData;
  final double progress;
  final bool isPlaying;
  final AnimationController animationController;
  final Color color;

  const _WaveformView({
    required this.waveformData,
    required this.progress,
    required this.isPlaying,
    required this.animationController,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    if (waveformData.isEmpty) {
      return SizedBox(
        height: 40,
        child: Center(
          child: Text(
            'Generating waveform...',
            style: TextStyle(
              fontSize: 11,
              color: color.withValues(alpha: 0.5),
            ),
          ),
        ),
      );
    } else {
      return AnimatedBuilder(
        animation: animationController,
        builder: (context, child) {
          return SizedBox(
            height: 40,
            child: CustomPaint(
              size: Size(double.infinity, 40),
              painter: _WaveformPainter(
                waveformData: waveformData,
                progress: progress,
                isPlaying: isPlaying,
                animationValue: animationController.value,
                color: color,
              ),
            ),
          );
        },
      );
    }
  }
}  // End of _WaveformView

class _WaveformPainter extends CustomPainter {
  final List<double> waveformData;
  final double progress;
  final bool isPlaying;
  final double animationValue;
  final Color color;

  _WaveformPainter({
    required this.waveformData,
    required this.progress,
    required this.isPlaying,
    required this.animationValue,
    required this.color,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (waveformData.isEmpty) return;
    
    final barWidth = size.width / waveformData.length;
    final progressX = size.width * progress;
    
    for (int i = 0; i < waveformData.length; i++) {
      final x = i * barWidth + barWidth / 2;
      final height = waveformData[i] * size.height * 0.8;
      final yCenter = size.height / 2;
      
      final isProgressed = x <= progressX;
      
      final paint = Paint()
        ..color = isProgressed 
            ? color.withValues(alpha: 0.8) 
            : color.withValues(alpha: 0.3)
        ..strokeWidth = (size.width / waveformData.length * 0.6).clamp(1.5, 4.0)
        ..strokeCap = StrokeCap.round
        ..style = PaintingStyle.stroke;
      
      // Animated pulse for playing
      double animationOffset = 0;
      if (isPlaying) {
        animationOffset = math.sin(animationValue * 2 * math.pi) * 2;
      }
      
      canvas.drawLine(
        Offset(x, yCenter - height / 2 + animationOffset),
        Offset(x, yCenter + height / 2 - animationOffset),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) {
    return oldDelegate is _WaveformPainter && 
        (oldDelegate.animationValue != animationValue ||
         oldDelegate.progress != progress ||
         oldDelegate.isPlaying != isPlaying);
  }
}

/// Play/Pause button
class _PlayPauseButton extends StatelessWidget {
  final bool isPlaying;
  final VoidCallback onPressed;
  final Color color;

  const _PlayPauseButton({
    required this.isPlaying,
    required this.onPressed,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(20),
        child: Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.15),
            shape: BoxShape.circle,
          ),
          child: Icon(
            isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
            size: 20,
            color: color,
          ),
        ),
      ),
    );
  }
}

/// Speed control button
class _SpeedButton extends StatelessWidget {
  final double speed;
  final VoidCallback onPressed;
  final Color color;

  const _SpeedButton({
    required this.speed,
    required this.onPressed,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.15),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: color.withValues(alpha: 0.3),
              width: 1,
            ),
          ),
          child: Text(
            '${speed.toStringAsFixed(1)}x',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              color: color,
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.status});
  final MessageStatus status;

  @override
  Widget build(BuildContext context) {
    return switch (status) {
      MessageStatus.sending => Icon(Icons.access_time_rounded, size: 13, color: Colors.white.withValues(alpha: 0.4)),
      MessageStatus.pending => Icon(Icons.access_time_rounded, size: 13, color: Colors.white.withValues(alpha: 0.4)),
      MessageStatus.sent => Icon(Icons.done_rounded, size: 13, color: Colors.white.withValues(alpha: 0.6)),
      MessageStatus.delivered => Icon(Icons.done_all_rounded, size: 13, color: MeshAppTheme.success),
      MessageStatus.read => Icon(Icons.done_all_rounded, size: 13, color: MeshAppTheme.info),
      MessageStatus.failed => Icon(Icons.error_outline_rounded, size: 13, color: MeshAppTheme.error),
      MessageStatus.transferring => Icon(Icons.sync_rounded, size: 13, color: Colors.white.withValues(alpha: 0.6)),
    };
  }
}
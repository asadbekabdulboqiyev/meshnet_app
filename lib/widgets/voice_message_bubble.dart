import 'package:flutter/material.dart';
import '../models/message_model.dart';
import 'audio_waveform.dart';

class VoiceMessageBubble extends StatefulWidget {
  final ChatMessage message;

  const VoiceMessageBubble({super.key, required this.message});

  @override
  State<VoiceMessageBubble> createState() => _VoiceMessageBubbleState();
}

class _VoiceMessageBubbleState extends State<VoiceMessageBubble> {
  bool _isPlaying = false;
  Duration _position = Duration.zero;
  late List<double> _amplitudes;

  @override
  void initState() {
    super.initState();
    _amplitudes = List.generate(40, (i) => (i % 3 == 0) ? 0.3 : 0.6 + (i % 5) * 0.08);
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: const BoxConstraints(maxWidth: 280),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: widget.message.fromMe
            ? const Color(0xFF00897B).withOpacity(0.9)
            : const Color(0xFF2A2A2A),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          GestureDetector(
            onTap: _togglePlayback,
            child: Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.15),
                shape: BoxShape.circle,
              ),
              child: Icon(
                _isPlaying ? Icons.pause : Icons.play_arrow,
                color: Colors.white,
                size: 24,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                AudioWaveform(
                  amplitudes: _amplitudes,
                  position: _position,
                  totalDuration: widget.message.audioDuration ?? Duration.zero,
                  height: 32,
                ),
                const SizedBox(height: 2),
                Text(
                  widget.message.displayDuration,
                  style: TextStyle(
                    color: Colors.white.withOpacity(0.6),
                    fontSize: 11,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _togglePlayback() {
    setState(() {
      _isPlaying = !_isPlaying;
      if (_isPlaying) {
        _simulatePlayback();
      }
    });
  }

  void _simulatePlayback() {
    Future.delayed(const Duration(milliseconds: 100), () {
      if (!mounted || !_isPlaying) return;
      final total = widget.message.audioDuration ?? const Duration(seconds: 30);
      setState(() {
        _position += const Duration(milliseconds: 100);
        if (_position >= total) {
          _position = Duration.zero;
          _isPlaying = false;
        }
      });
      if (_isPlaying) _simulatePlayback();
    });
  }
}

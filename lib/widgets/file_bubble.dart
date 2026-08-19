import 'dart:io';
import 'package:flutter/material.dart';
import '../models/message_model.dart';

class FileBubble extends StatelessWidget {
  final ChatMessage message;
  final VoidCallback? onTap;

  const FileBubble({super.key, required this.message, this.onTap});

  @override
  Widget build(BuildContext context) {
    final isImage = message.mimeType?.startsWith('image/') == true;
    final filePath = message.localPath ?? message.remotePath;

    return GestureDetector(
      onTap: onTap,
      child: Container(
        constraints: const BoxConstraints(maxWidth: 260),
        decoration: BoxDecoration(
          color: message.fromMe
              ? const Color(0xFF00897B).withOpacity(0.9)
              : const Color(0xFF2A2A2A),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (isImage && filePath != null && File(filePath).existsSync())
              ClipRRect(
                borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
                child: Image.file(
                  File(filePath),
                  width: 220,
                  height: 160,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => _buildFileIcon(),
                ),
              )
            else
              _buildFileIcon(),
            Padding(
              padding: const EdgeInsets.all(10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    message.fileName ?? 'File',
                    style: const TextStyle(color: Colors.white, fontSize: 13),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 4),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        message.displaySize,
                        style: TextStyle(
                          color: Colors.white.withOpacity(0.6),
                          fontSize: 11,
                        ),
                      ),
                      if (message.transferProgress != null &&
                          message.transferProgress! < 1.0) ...[
                        const SizedBox(width: 8),
                        SizedBox(
                          width: 60,
                          child: LinearProgressIndicator(
                            value: message.transferProgress,
                            backgroundColor: Colors.white24,
                            valueColor: const AlwaysStoppedAnimation(Colors.white),
                            minHeight: 3,
                          ),
                        ),
                        const SizedBox(width: 4),
                        Text(
                          '${((message.transferProgress ?? 0) * 100).toInt()}%',
                          style: TextStyle(
                            color: Colors.white.withOpacity(0.6),
                            fontSize: 11,
                          ),
                        ),
                      ],
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFileIcon() {
    IconData icon;
    Color color;

    if (message.mimeType?.startsWith('video/') == true) {
      icon = Icons.videocam;
      color = Colors.purple;
    } else if (message.mimeType?.contains('pdf') == true) {
      icon = Icons.picture_as_pdf;
      color = Colors.red;
    } else if (message.mimeType?.startsWith('audio/') == true) {
      icon = Icons.audio_file;
      color = Colors.orange;
    } else if (message.mimeType?.contains('zip') == true ||
        message.mimeType?.contains('rar') == true) {
      icon = Icons.archive;
      color = Colors.amber;
    } else {
      icon = Icons.insert_drive_file;
      color = Colors.blue;
    }

    return Container(
      width: 220,
      height: 80,
      decoration: BoxDecoration(
        color: color.withOpacity(0.2),
        borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
      ),
      child: Center(
        child: Icon(icon, size: 36, color: color),
      ),
    );
  }
}

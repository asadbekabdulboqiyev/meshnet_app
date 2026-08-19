import 'dart:io';
import 'package:flutter/material.dart';
import 'package:photo_view/photo_view.dart';

class MediaPreviewScreen extends StatelessWidget {
  final String filePath;
  final String? title;

  const MediaPreviewScreen({super.key, required this.filePath, this.title});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        title: title != null ? Text(title!, style: const TextStyle(color: Colors.white)) : null,
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: PhotoView(
        imageProvider: FileImage(File(filePath)),
        minScale: PhotoViewComputedScale.contained,
        maxScale: PhotoViewComputedScale.covered * 3,
        backgroundDecoration: const BoxDecoration(color: Colors.black),
      ),
    );
  }
}

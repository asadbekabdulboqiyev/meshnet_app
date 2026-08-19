import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../core/mesh_service.dart';
import '../theme/app_theme.dart';

/// Pairing screen — no gradient.
class PairingView extends ConsumerStatefulWidget {
  const PairingView({super.key, required this.displayName});

  final String displayName;

  @override
  ConsumerState<PairingView> createState() => _PairingViewState();
}

class _PairingViewState extends ConsumerState<PairingView> {
  String? _deviceId;
  String? _publicKey;
  bool _showScanner = false;
  StreamSubscription<Map<String, dynamic>>? _sub;

  @override
  void initState() {
    super.initState();
    _loadIdentity();
    _sub = ref.read(meshServiceProvider).events.listen((event) {
      if (!mounted) return;
      if (event['event'] == 'pairResult') {
        final success = event['success'] == true;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Row(
              children: [
                Icon(
                  success ? Icons.check_circle_rounded : Icons.error_rounded,
                  color: success ? MeshAppTheme.success : MeshAppTheme.error,
                  size: 20,
                ),
                const SizedBox(width: 8),
                Text(success ? 'Pairing successful' : 'Not confirmed'),
              ],
            ),
          ),
        );
      }
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _loadIdentity() async {
    // Engine may not be ready yet — wait with retry
    for (var attempt = 0; attempt < 10; attempt++) {
      final id = await ref.read(meshServiceProvider).getLocalIdentity();
      if (id != null) {
        setState(() {
          _deviceId = id['deviceId'] as String?;
          _publicKey = id['publicKey'] as String?;
        });
        return;
      }
      await Future<void>.delayed(const Duration(milliseconds: 500));
    }
  }

  String get _qrData => jsonEncode({
    'deviceId': _deviceId ?? '',
    'publicKey': _publicKey ?? '',
    'name': widget.displayName,
  });

  Future<void> _handleScan(String qrText) async {
    final service = ref.read(meshServiceProvider);
    try {
      final data = jsonDecode(qrText) as Map<String, dynamic>;
      final deviceId = data['deviceId'] as String? ?? '';
      final publicKey = data['publicKey'] as String? ?? '';
      if (deviceId.isEmpty || publicKey.isEmpty) throw const FormatException('Invalid QR');
      final ok = await service.pairWithPeer(deviceId, publicKey);
      if (!mounted) return;
      setState(() => _showScanner = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Row(
            children: [
              Icon(
                ok ? Icons.check_circle_rounded : Icons.error_rounded,
                color: ok ? MeshAppTheme.success : MeshAppTheme.error,
                size: 20,
              ),
              const SizedBox(width: 8),
              Text(ok ? 'Pairing completed' : 'Failed'),
            ],
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _showScanner = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error: $e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: _showScanner
          ? AppBar(
              title: const Text('Scanning'),
              leading: IconButton(
                icon: const Icon(Icons.close_rounded),
                onPressed: () => setState(() => _showScanner = false),
              ),
            )
          : AppBar(title: const Text('Pairing')),
      body: _showScanner
          ? _ScannerView(onDetect: _handleScan)
          : _PairingBody(
              qrData: _qrData,
              onScanTap: () => setState(() => _showScanner = true),
            ),
    );
  }
}

class _PairingBody extends StatelessWidget {
  const _PairingBody({required this.qrData, required this.onScanTap});

  final String qrData;
  final VoidCallback onScanTap;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
      children: [
        // QR code
        Center(
          child: Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: MeshAppTheme.border, width: 1),
            ),
            child: qrData.contains('""')
                ? const SizedBox(
                    width: 200,
                    height: 200,
                    child: Center(child: CircularProgressIndicator(color: MeshAppTheme.primary)),
                  )
                : QrImageView(
                    data: qrData,
                    version: QrVersions.auto,
                    size: 200,
                    backgroundColor: Colors.white,
                  ),
          ),
        ),
        const SizedBox(height: 24),
        // Title
        const Text(
          'Show your QR code',
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w700,
            color: MeshAppTheme.textWhite,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'Another device scans\nyour QR code',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 14, color: MeshAppTheme.textGray, height: 1.5),
        ),
        const SizedBox(height: 32),
        // Scan button
        GestureDetector(
          onTap: onScanTap,
          child: Container(
            width: double.infinity,
            height: 52,
            decoration: BoxDecoration(
              color: MeshAppTheme.primary,
              borderRadius: BorderRadius.circular(14),
            ),
            child: const Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.qr_code_scanner_rounded, color: Colors.white, size: 22),
                SizedBox(width: 10),
                Text(
                  'SCAN OTHER QR',
                  style: TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w800,
                    fontSize: 14,
                    letterSpacing: 0.5,
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _ScannerView extends StatefulWidget {
  const _ScannerView({required this.onDetect});
  final Future<void> Function(String qrText) onDetect;

  @override
  State<_ScannerView> createState() => _ScannerViewState();
}

class _ScannerViewState extends State<_ScannerView> {
  final MobileScannerController _controller = MobileScannerController();
  bool _handled = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        MobileScanner(
          controller: _controller,
          onDetect: (capture) async {
            if (_handled) return;
            final barcodes = capture.barcodes;
            if (barcodes.isNotEmpty) {
              final raw = barcodes.first.rawValue;
              if (raw != null) {
                _handled = true;
                await widget.onDetect(raw);
              }
            }
          },
        ),
        // Scan frame
        Center(
          child: Container(
            width: 260,
            height: 260,
            decoration: BoxDecoration(
              border: Border.all(color: MeshAppTheme.primary, width: 3),
              borderRadius: BorderRadius.circular(20),
            ),
          ),
        ),
        // Instructions
        Positioned(
          bottom: 100,
          left: 0,
          right: 0,
          child: Container(
            margin: const EdgeInsets.symmetric(horizontal: 40),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
            decoration: BoxDecoration(
              color: MeshAppTheme.bgDeep.withValues(alpha: 0.85),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: MeshAppTheme.border, width: 1),
            ),
            child: Text(
              'Place QR code inside the frame',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: MeshAppTheme.primary,
                fontSize: 14,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ),
      ],
    );
  }
}

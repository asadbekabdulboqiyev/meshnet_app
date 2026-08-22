import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/app_theme.dart';
import 'home_screen.dart';
import '../core/mesh_service.dart';

/// Illustration types for onboarding pages
enum _IllustrationType {
  meshNetwork,
  chatSecure,
  fileShare,
  collaborate,
  gateway,
  emergency,
  setName,
  permissions,
  ready,
}

/// Data class for onboarding page content
class _OnboardingPageData {
  final String title;
  final String subtitle;
  final _IllustrationType illustration;
  final String description;
  final String primaryAction;
  final bool requiresInput;
  final bool requiresPermissions;
  final bool isFinal;

  const _OnboardingPageData({
    required this.title,
    required this.subtitle,
    required this.illustration,
    required this.description,
    required this.primaryAction,
    this.requiresInput = false,
    this.requiresPermissions = false,
    this.isFinal = false,
  });
}

/// Onboarding pages data (defined outside class for const usage)
const _pages = [
  _OnboardingPageData(
    title: 'Welcome to MeshNet',
    subtitle: 'Offline P2P Mesh Networking',
    illustration: _IllustrationType.meshNetwork,
    description:
        'MeshNet creates a decentralized network between nearby devices using Bluetooth Low Energy and Wi-Fi Direct. No internet, no SIM card, no server required.',
    primaryAction: 'Get Started',
  ),
  _OnboardingPageData(
    title: 'Chat Securely',
    subtitle: 'End-to-End Encrypted',
    illustration: _IllustrationType.chatSecure,
    description:
        'All messages are encrypted with X25519 + ChaCha20-Poly1305 and Double Ratchet forward secrecy. Only you and your recipient can read them.',
    primaryAction: 'Next',
  ),
  _OnboardingPageData(
    title: 'Share Files Offline',
    subtitle: 'Chunked, Verified, Resumable',
    illustration: _IllustrationType.fileShare,
    description:
        'Send files of any size. They are split into 64KB chunks, each verified with SHA-256. Interrupted transfers resume automatically.',
    primaryAction: 'Next',
  ),
  _OnboardingPageData(
    title: 'Collaborate in Real-Time',
    subtitle: 'Whiteboard, Notes & Polls',
    illustration: _IllustrationType.collaborate,
    description:
        'Draw together on a shared canvas, edit documents with last-writer-wins sync, and run live polls — all over the mesh.',
    primaryAction: 'Next',
  ),
  _OnboardingPageData(
    title: 'Share Your Internet',
    subtitle: 'Mesh Gateway',
    illustration: _IllustrationType.gateway,
    description:
        'One device with internet can share it as an HTTP/CONNECT proxy. Other devices route traffic through the mesh.',
    primaryAction: 'Next',
  ),
  _OnboardingPageData(
    title: 'Emergency Broadcasts',
    subtitle: 'Priority Alerts',
    illustration: _IllustrationType.emergency,
    description:
        'Send CRITICAL alerts that flood the mesh instantly. Recipients acknowledge receipt. Works even when internet is down.',
    primaryAction: 'Next',
  ),
  _OnboardingPageData(
    title: 'Set Your Name',
    subtitle: 'How others will see you',
    illustration: _IllustrationType.setName,
    description:
        'Choose a display name. It will be sanitized into a valid .mesh hostname (letters, numbers, hyphens only).',
    primaryAction: 'Continue',
    requiresInput: true,
  ),
  _OnboardingPageData(
    title: 'Permissions Required',
    subtitle: 'For mesh to work',
    illustration: _IllustrationType.permissions,
    description:
        'MeshNet needs:\n• Location (BLE scanning)\n• Nearby Wi-Fi Devices (Wi-Fi Direct)\n• Bluetooth (advertising & scanning)\n• Notifications (background alerts)',
    primaryAction: 'Grant Permissions',
    requiresPermissions: true,
  ),
  _OnboardingPageData(
    title: 'You\'re Ready!',
    subtitle: 'Start building your mesh',
    illustration: _IllustrationType.ready,
    description:
        'Tap "Start Mesh" to begin. Other MeshNet devices nearby will appear automatically. No configuration needed.',
    primaryAction: 'Start Mesh',
    isFinal: true,
  ),
];

/// Onboarding wizard shown on first launch
class OnboardingScreen extends ConsumerStatefulWidget {
  const OnboardingScreen({super.key});

  @override
  ConsumerState<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends ConsumerState<OnboardingScreen>
    with TickerProviderStateMixin {
  late final PageController _pageController;
  late final AnimationController _animCtrl;
  int _currentPage = 0;
  final _nameController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _pageController = PageController();
    _animCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 400),
    )..forward();
  }

  @override
  void dispose() {
    _pageController.dispose();
    _animCtrl.dispose();
    _nameController.dispose();
    super.dispose();
  }

  void _nextPage() {
    if (_currentPage < _pages.length - 1) {
      _pageController.nextPage(
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOutCubic,
      );
    }
  }

  void _previousPage() {
    if (_currentPage > 0) {
      _pageController.previousPage(
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOutCubic,
      );
    }
  }

  Future<void> _completeOnboarding() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('onboarding_completed', true);
    await prefs.setString('display_name', _nameController.text.trim());

    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 350),
        pageBuilder: (_, _, _) => const HomeScreen(),
        transitionsBuilder: (_, anim, _, child) =>
            FadeTransition(opacity: anim, child: child),
      ),
    );
  }

  Future<void> _requestPermissions() async {
    final meshService = ref.read(meshServiceProvider);
    await meshService.requestMeshPermissions();
    _nextPage();
  }

  @override
  Widget build(BuildContext context) {
    final isLastPage = _currentPage == _pages.length - 1;

    return Scaffold(
      backgroundColor: MeshAppTheme.bgDeep,
      body: SafeArea(
        child: Column(
          children: [
            // Skip button (top right)
            if (!isLastPage)
              Align(
                alignment: Alignment.topRight,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: TextButton(
                    onPressed: _completeOnboarding,
                    child: Text(
                      'Skip',
                      style: TextStyle(
                        color: MeshAppTheme.textDim,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ),
              ),

            // Page indicator dots
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: List.generate(_pages.length, (i) {
                  return AnimatedContainer(
                    duration: const Duration(milliseconds: 250),
                    margin: const EdgeInsets.symmetric(horizontal: 4),
                    width: i == _currentPage ? 24 : 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: i == _currentPage
                          ? MeshAppTheme.primary
                          : MeshAppTheme.textDim.withValues(alpha: 0.3),
                      borderRadius: BorderRadius.circular(4),
                    ),
                  );
                }),
              ),
            ),

            // Page content
            Expanded(
              child: PageView.builder(
                controller: _pageController,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: _pages.length,
                onPageChanged: (i) => setState(() => _currentPage = i),
                itemBuilder: (context, i) {
                  final p = _pages[i];
                  return _OnboardingPage(
                    page: p,
                    nameController: _nameController,
                    onNext: _nextPage,
                    onPrevious: _previousPage,
                    onComplete: _completeOnboarding,
                    onPermissions: _requestPermissions,
                    isCurrent: i == _currentPage,
                    animCtrl: _animCtrl,
                  );
                },
              ),
            ),

            // Navigation buttons (bottom)
            if (!isLastPage)
              Padding(
                padding: const EdgeInsets.all(24),
                child: Row(
                  children: [
                    if (_currentPage > 0)
                      Expanded(
                        child: OutlinedButton(
                          onPressed: _previousPage,
                          style: OutlinedButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            side: BorderSide(color: MeshAppTheme.border),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                          ),
                          child: const Text('Back'),
                        ),
                      )
                    else
                      const Expanded(child: SizedBox()),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton(
                        onPressed: _currentPage == _pages.length - 2
                            ? _requestPermissions
                            : _nextPage,
                        style: FilledButton.styleFrom(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          backgroundColor: MeshAppTheme.primary,
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        child: Text(_pages[_currentPage].primaryAction),
                      ),
                    ),
                  ],
                ),
              )
            else
              Padding(
                padding: const EdgeInsets.all(24),
                child: FilledButton(
                  onPressed: _completeOnboarding,
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    backgroundColor: MeshAppTheme.primary,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    minimumSize: const Size(double.infinity, 56),
                  ),
                  child: const Text(
                    'Start Mesh',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }


}

/// Page widget for a single onboarding step
class _OnboardingPage extends StatelessWidget {
  final _OnboardingPageData page;
  final TextEditingController nameController;
  final VoidCallback onNext;
  final VoidCallback onPrevious;
  final VoidCallback onComplete;
  final VoidCallback onPermissions;
  final bool isCurrent;
  final AnimationController animCtrl;

  const _OnboardingPage({
    required this.page,
    required this.nameController,
    required this.onNext,
    required this.onPrevious,
    required this.onComplete,
    required this.onPermissions,
    required this.isCurrent,
    required this.animCtrl,
  });

  @override
  Widget build(BuildContext context) {
    if (!isCurrent) return const SizedBox.shrink();

    return FadeTransition(
      opacity: CurvedAnimation(parent: animCtrl, curve: Curves.easeOut),
      child: SlideTransition(
        position: Tween<Offset>(
          begin: const Offset(0, 0.1),
          end: Offset.zero,
        ).animate(CurvedAnimation(parent: animCtrl, curve: Curves.easeOutCubic)),
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              // Illustration
              _OnboardingIllustration(type: page.illustration),
              const SizedBox(height: 32),

              // Title & Subtitle
              Text(
                page.title,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.w800,
                  color: MeshAppTheme.textWhite,
                  letterSpacing: -0.5,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                page.subtitle,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w500,
                  color: MeshAppTheme.primary,
                  letterSpacing: 0.5,
                ),
              ),
              const SizedBox(height: 24),

              // Description
              Text(
                page.description,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 15,
                  color: MeshAppTheme.textGray,
                  height: 1.6,
                ),
              ),

              // Input field (for name page)
              if (page.requiresInput) ...[
                const SizedBox(height: 32),
                TextField(
                  controller: nameController,
                  autofocus: true,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w600,
                    color: MeshAppTheme.textWhite,
                  ),
                  decoration: InputDecoration(
                    hintText: 'Enter your name',
                    hintStyle: TextStyle(color: MeshAppTheme.textDim),
                    filled: true,
                    fillColor: MeshAppTheme.bgCard,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(16),
                      borderSide: BorderSide(color: MeshAppTheme.border),
                    ),
                    focusedBorder: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(16),
                      borderSide: BorderSide(color: MeshAppTheme.primary, width: 2),
                    ),
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 24,
                      vertical: 16,
                    ),
                  ),
                  onSubmitted: (_) => onNext(),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Illustration widget for onboarding pages
class _OnboardingIllustration extends StatelessWidget {
  final _IllustrationType type;

  const _OnboardingIllustration({required this.type});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 160,
      height: 160,
      child: CustomPaint(
        painter: _IllustrationPainter(type),
      ),
    );
  }
}

/// Custom painter for onboarding illustrations
class _IllustrationPainter extends CustomPainter {
  final _IllustrationType type;

  _IllustrationPainter(this.type);

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2;

    final paint = Paint()
      ..style = PaintingStyle.fill
      ..color = MeshAppTheme.primary.withValues(alpha: 0.1);

    final accentPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.5
      ..color = MeshAppTheme.primary;

    final fillPaint = Paint()
      ..style = PaintingStyle.fill
      ..color = MeshAppTheme.primary;

    switch (type) {
      case _IllustrationType.meshNetwork:
        _drawMeshNetwork(canvas, center, radius, paint, accentPaint);
        break;
      case _IllustrationType.chatSecure:
        _drawChatSecure(canvas, center, radius, paint, fillPaint);
        break;
      case _IllustrationType.fileShare:
        _drawFileShare(canvas, center, radius, paint, fillPaint);
        break;
      case _IllustrationType.collaborate:
        _drawCollaborate(canvas, center, radius, paint, fillPaint);
        break;
      case _IllustrationType.gateway:
        _drawGateway(canvas, center, radius, paint, fillPaint);
        break;
      case _IllustrationType.emergency:
        _drawEmergency(canvas, center, radius, paint, fillPaint);
        break;
      case _IllustrationType.setName:
        _drawSetName(canvas, center, radius, paint, fillPaint);
        break;
      case _IllustrationType.permissions:
        _drawPermissions(canvas, center, radius, paint, fillPaint);
        break;
      case _IllustrationType.ready:
        _drawReady(canvas, center, radius, paint, fillPaint);
        break;
    }
  }

  void _drawMeshNetwork(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    final nodes = <Offset>[
      center,
      center + Offset(radius * 0.6, 0),
      center + Offset(-radius * 0.6, 0),
      center + Offset(0, -radius * 0.6),
      center + Offset(0, radius * 0.6),
      center + Offset(radius * 0.4, -radius * 0.4),
      center + Offset(-radius * 0.4, -radius * 0.4),
      center + Offset(radius * 0.4, radius * 0.4),
      center + Offset(-radius * 0.4, radius * 0.4),
    ];

    final stroke = Paint()
      ..color = MeshAppTheme.primary.withValues(alpha: 0.3)
      ..strokeWidth = 1.5
      ..style = PaintingStyle.stroke;

    for (int i = 0; i < nodes.length; i++) {
      for (int j = i + 1; j < nodes.length; j++) {
        if ((nodes[i] - nodes[j]).distance < radius * 0.8) {
          canvas.drawLine(nodes[i], nodes[j], stroke);
        }
      }
    }

    for (final node in nodes) {
      canvas.drawCircle(node, 8, Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.2));
      canvas.drawCircle(node, 4, Paint()..color = MeshAppTheme.primary);
    }
  }

  void _drawChatSecure(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    final rect = RRect.fromRectAndRadius(
      Rect.fromCenter(center: center, width: radius * 1.3, height: radius * 0.9),
      Radius.circular(16),
    );
    canvas.drawRRect(rect, Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.15));
    canvas.drawRRect(rect, Paint()..color = MeshAppTheme.primary..style = PaintingStyle.stroke..strokeWidth = 2);
    _drawLock(canvas, center, radius * 0.4);
  }

  void _drawFileShare(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    final rect = RRect.fromRectAndRadius(
      Rect.fromCenter(center: center, width: radius * 1.1, height: radius * 1.3),
      Radius.circular(12),
    );
    canvas.drawRRect(rect, Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.15));
    canvas.drawRRect(rect, Paint()..color = MeshAppTheme.primary..style = PaintingStyle.stroke..strokeWidth = 2);

    final cornerPath = Path()
      ..moveTo(center.dx + radius * 0.4, center.dy - radius * 0.5)
      ..lineTo(center.dx + radius * 0.4, center.dy - radius * 0.1)
      ..lineTo(center.dx + radius * 0.8, center.dy - radius * 0.5)
      ..close();
    canvas.drawPath(cornerPath, Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.3));
    _drawArrowDown(canvas, center + Offset(0, radius * 0.2), radius * 0.4);
  }

  void _drawCollaborate(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    final paint = Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.15)..style = PaintingStyle.fill;

    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromCenter(center: center + Offset(-radius * 0.5, -radius * 0.3), width: radius * 0.7, height: radius * 0.5),
        Radius.circular(8),
      ),
      paint,
    );

    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromCenter(center: center + Offset(radius * 0.5, -radius * 0.3), width: radius * 0.7, height: radius * 0.5),
        Radius.circular(8),
      ),
      paint,
    );

    canvas.drawCircle(center + Offset(0, radius * 0.5), radius * 0.35, paint);

    final linePaint = Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.3)..strokeWidth = 1.5;
    canvas.drawLine(center + Offset(-radius * 0.5, -radius * 0.3), center + Offset(0, radius * 0.5), linePaint);
    canvas.drawLine(center + Offset(radius * 0.5, -radius * 0.3), center + Offset(0, radius * 0.5), linePaint);
  }

  void _drawGateway(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    canvas.drawCircle(center, radius * 0.5, Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.15));
    canvas.drawCircle(center, radius * 0.5, Paint()..color = MeshAppTheme.primary..style = PaintingStyle.stroke..strokeWidth = 2);

    for (int i = 1; i <= 3; i++) {
      final r = radius * 0.5 + i * 12;
      final arc = Path()
        ..addArc(
          Rect.fromCircle(center: center, radius: r),
          -60,
          120,
        );
      canvas.drawPath(
        arc,
        Paint()
          ..color = MeshAppTheme.primary.withValues(alpha: 0.3 - i * 0.08)
          ..strokeWidth = 2
          ..style = PaintingStyle.stroke,
      );
    }
    canvas.drawCircle(center, 6, Paint()..color = MeshAppTheme.primary);
  }

  void _drawEmergency(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    final path = Path()
      ..moveTo(center.dx, center.dy - radius * 0.6)
      ..lineTo(center.dx - radius * 0.5, center.dy + radius * 0.4)
      ..lineTo(center.dx + radius * 0.5, center.dy + radius * 0.4)
      ..close();

    canvas.drawPath(path, Paint()..color = MeshAppTheme.error.withValues(alpha: 0.15));
    canvas.drawPath(path, Paint()..color = MeshAppTheme.error..style = PaintingStyle.stroke..strokeWidth = 2);

    canvas.drawCircle(center + Offset(0, -radius * 0.1), 4, Paint()..color = MeshAppTheme.error);
    canvas.drawRect(
      Rect.fromCenter(center: center + Offset(0, radius * 0.2), width: 3, height: 18),
      Paint()..color = MeshAppTheme.error,
    );
  }

  void _drawSetName(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    canvas.drawCircle(center + Offset(0, -radius * 0.15), radius * 0.35,
        Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.15));
    canvas.drawCircle(center + Offset(0, -radius * 0.15), radius * 0.35,
        Paint()..color = MeshAppTheme.primary..style = PaintingStyle.stroke..strokeWidth = 2);

    final bodyPath = Path()
      ..moveTo(center.dx - radius * 0.3, center.dy + radius * 0.25)
      ..quadraticBezierTo(center.dx, center.dy + radius * 0.6, center.dx + radius * 0.3, center.dy + radius * 0.25)
      ..lineTo(center.dx + radius * 0.3, center.dy + radius * 0.7)
      ..lineTo(center.dx - radius * 0.3, center.dy + radius * 0.7)
      ..close();
    canvas.drawPath(bodyPath, Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.15));
    canvas.drawPath(bodyPath, Paint()..color = MeshAppTheme.primary..style = PaintingStyle.stroke..strokeWidth = 2);

    _drawPencil(canvas, center + Offset(radius * 0.3, -radius * 0.2), radius * 0.2);
  }

  void _drawPermissions(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    final shieldPath = Path()
      ..moveTo(center.dx, center.dy - radius * 0.5)
      ..lineTo(center.dx + radius * 0.5, center.dy - radius * 0.1)
      ..lineTo(center.dx + radius * 0.5, center.dy + radius * 0.3)
      ..quadraticBezierTo(center.dx + radius * 0.5, center.dy + radius * 0.5, center.dx, center.dy + radius * 0.5)
      ..quadraticBezierTo(center.dx - radius * 0.5, center.dy + radius * 0.5, center.dx - radius * 0.5, center.dy + radius * 0.3)
      ..lineTo(center.dx - radius * 0.5, center.dy - radius * 0.1)
      ..close();

    canvas.drawPath(shieldPath, Paint()..color = MeshAppTheme.primary.withValues(alpha: 0.15));
    canvas.drawPath(shieldPath, Paint()..color = MeshAppTheme.primary..style = PaintingStyle.stroke..strokeWidth = 2);

    final checkPath = Path()
      ..moveTo(center.dx - radius * 0.15, center.dy)
      ..lineTo(center.dx - radius * 0.05, center.dy + radius * 0.1)
      ..lineTo(center.dx + radius * 0.2, center.dy - radius * 0.15);
    canvas.drawPath(
      checkPath,
      Paint()..color = MeshAppTheme.success..strokeWidth = 3..style = PaintingStyle.stroke..strokeCap = StrokeCap.round,
    );
  }

  void _drawReady(Canvas canvas, Offset center, double radius, Paint fill, Paint stroke) {
    canvas.drawCircle(center, radius * 0.55, Paint()..color = MeshAppTheme.success.withValues(alpha: 0.15));
    canvas.drawCircle(center, radius * 0.55, Paint()..color = MeshAppTheme.success..style = PaintingStyle.stroke..strokeWidth = 3);

    final checkPath = Path()
      ..moveTo(center.dx - radius * 0.25, center.dy)
      ..lineTo(center.dx - radius * 0.05, center.dy + radius * 0.2)
      ..lineTo(center.dx + radius * 0.3, center.dy - radius * 0.25);
    canvas.drawPath(
      checkPath,
      Paint()
        ..color = MeshAppTheme.success
        ..strokeWidth = 4
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round,
    );
  }

  void _drawLock(Canvas canvas, Offset center, double radius) {
    final lockRect = RRect.fromRectAndRadius(
      Rect.fromCenter(center: center, width: radius * 0.8, height: radius * 1.0),
      Radius.circular(8),
    );
    canvas.drawRRect(lockRect, Paint()..color = MeshAppTheme.primary);

    final shacklePath = Path()
      ..moveTo(center.dx - radius * 0.25, center.dy - radius * 0.15)
      ..lineTo(center.dx - radius * 0.25, center.dy - radius * 0.5)
      ..lineTo(center.dx + radius * 0.25, center.dy - radius * 0.5)
      ..lineTo(center.dx + radius * 0.25, center.dy - radius * 0.15);
    canvas.drawPath(
      shacklePath,
      Paint()..color = Colors.white..strokeWidth = 3..style = PaintingStyle.stroke..strokeCap = StrokeCap.round,
    );
  }

  void _drawArrowDown(Canvas canvas, Offset center, double size) {
    final path = Path()
      ..moveTo(center.dx, center.dy + size * 0.5)
      ..lineTo(center.dx - size * 0.4, center.dy - size * 0.3)
      ..moveTo(center.dx, center.dy + size * 0.5)
      ..lineTo(center.dx + size * 0.4, center.dy - size * 0.3);
    canvas.drawPath(
      path,
      Paint()..color = MeshAppTheme.primary..strokeWidth = 3..style = PaintingStyle.stroke..strokeCap = StrokeCap.round,
    );
  }

  void _drawPencil(Canvas canvas, Offset center, double size) {
    final path = Path()
      ..moveTo(center.dx - size * 0.3, center.dy + size * 0.3)
      ..lineTo(center.dx + size * 0.3, center.dy - size * 0.3)
      ..lineTo(center.dx + size * 0.5, center.dy - size * 0.1)
      ..lineTo(center.dx - size * 0.1, center.dy + size * 0.5)
      ..close();
    canvas.drawPath(path, Paint()..color = MeshAppTheme.primary);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

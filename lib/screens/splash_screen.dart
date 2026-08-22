import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/app_theme.dart';
import 'home_screen.dart';
import 'onboarding_screen.dart';

/// Splash screen — no gradient, clean.
class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen>
    with TickerProviderStateMixin {
  late final AnimationController _mainCtrl;
  late final AnimationController _pulseCtrl;
  late final Animation<double> _fadeIn;
  late final Animation<double> _scaleIn;

  @override
  void initState() {
    super.initState();
    _mainCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    );
    _pulseCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1800),
    )..repeat(reverse: true);

    _fadeIn = CurvedAnimation(parent: _mainCtrl, curve: Curves.easeOut);
    _scaleIn = Tween<double>(begin: 0.6, end: 1.0).animate(
      CurvedAnimation(parent: _mainCtrl, curve: Curves.easeOutBack),
    );
    _mainCtrl.forward();

    _checkOnboarding();
  }

  Future<void> _checkOnboarding() async {
    final prefs = await SharedPreferences.getInstance();
    final completed = prefs.getBool('onboarding_completed') ?? false;

    await Future.delayed(const Duration(milliseconds: 2000));

    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 350),
        pageBuilder: (_, _, _) => completed ? const HomeScreen() : const OnboardingScreen(),
        transitionsBuilder: (_, anim, _, child) =>
            FadeTransition(opacity: anim, child: child),
      ),
    );
  }

  @override
  void dispose() {
    _mainCtrl.dispose();
    _pulseCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: MeshAppTheme.bgDeep,
      body: Center(
        child: FadeTransition(
          opacity: _fadeIn,
          child: ScaleTransition(
            scale: _scaleIn,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // Logo container
                AnimatedBuilder(
                  animation: _pulseCtrl,
                  builder: (context, child) {
                    return Container(
                      width: 96,
                      height: 96,
                      decoration: BoxDecoration(
                        color: MeshAppTheme.primary.withValues(
                          alpha: 0.08 + _pulseCtrl.value * 0.04,
                        ),
                        shape: BoxShape.circle,
                      ),
                      child: child,
                    );
                  },
                  child: Container(
                    width: 76,
                    height: 76,
                    decoration: const BoxDecoration(
                      color: MeshAppTheme.primary,
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(
                      Icons.hub_rounded,
                      color: Colors.white,
                      size: 38,
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                // Nomi
                const Text(
                  'MeshNet',
                  style: TextStyle(
                    fontSize: 32,
                    fontWeight: FontWeight.w800,
                    color: MeshAppTheme.textWhite,
                    letterSpacing: -1,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'OFFLINE MESH NETWORK',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: MeshAppTheme.primary,
                    letterSpacing: 3,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'theme/app_theme.dart';
import 'screens/splash_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    statusBarBrightness: Brightness.dark,
  ));
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  runApp(const ProviderScope(child: MeshNetApp()));
}

class MeshNetApp extends StatelessWidget {
  const MeshNetApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MeshNet',
      debugShowCheckedModeBanner: false,
      theme: MeshAppTheme.light(),
      darkTheme: MeshAppTheme.dark(),
      themeMode: ThemeMode.dark,
      home: const SplashScreen(),
    );
  }
}

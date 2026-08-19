import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:permission_handler/permission_handler.dart';

import '../core/mesh_service.dart';
import '../core/permissions.dart';
import '../theme/app_theme.dart';
import 'network_view.dart';
import 'contacts_view.dart';
import 'pairing_view.dart';

/// Main screen — no gradient, clean nav bar.
class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  int _index = 0;
  List<Permission> _deniedPermissions = [];

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    final service = ref.read(meshServiceProvider);
    service.connect();
    _deniedPermissions = await requestMeshPermissions();
    if (!mounted) return;
    setState(() {});
    await service.initEngine('MeshNet User');
    await service.startNode();
    service.events.listen((event) {
      if (!mounted) return;
      setState(() {});
      // Show floating snackbar when a new message arrives
      if (event['event'] == 'messageReceived') {
        final from = event['fromDeviceId'] as String? ?? '';
        final message = event['message'] as String? ?? '';
        if (from.isNotEmpty && message.isNotEmpty) {
          ScaffoldMessenger.of(context).hideCurrentSnackBar();
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Row(
                children: [
                  const Icon(Icons.message_rounded, color: MeshAppTheme.primary, size: 18),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          from.length > 12 ? '${from.substring(0, 12)}...' : from,
                          style: const TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                            color: MeshAppTheme.primary,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          message,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 13, color: Colors.white),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              duration: const Duration(seconds: 3),
              behavior: SnackBarBehavior.floating,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            ),
          );
        }
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final screens = [
      const NetworkView(),
      const ContactsView(),
      PairingView(displayName: 'MeshNet User'),
    ];

    if (_deniedPermissions.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).hideCurrentSnackBar();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('Permissions missing — mesh networking won\'t work'),
            action: SnackBarAction(
              label: 'SETTINGS',
              textColor: MeshAppTheme.primary,
              onPressed: () async {
                await openMeshAppSettings();
                final missing = await missingMeshPermissions();
                if (!mounted) return;
                setState(() => _deniedPermissions = missing);
              },
            ),
            duration: const Duration(seconds: 4),
          ),
        );
        _deniedPermissions = [];
      });
    }

    return Scaffold(
      body: IndexedStack(index: _index, children: screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        height: 66,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.hub_outlined, size: 24),
            selectedIcon: Icon(Icons.hub_rounded, size: 24, color: MeshAppTheme.primary),
            label: 'Network',
          ),
          NavigationDestination(
            icon: Icon(Icons.forum_outlined, size: 24),
            selectedIcon: Icon(Icons.forum_rounded, size: 24, color: MeshAppTheme.primary),
            label: 'Chats',
          ),
          NavigationDestination(
            icon: Icon(Icons.qr_code_2_outlined, size: 24),
            selectedIcon: Icon(Icons.qr_code_2_rounded, size: 24, color: MeshAppTheme.primary),
            label: 'Pair',
          ),
        ],
      ),
    );
  }
}

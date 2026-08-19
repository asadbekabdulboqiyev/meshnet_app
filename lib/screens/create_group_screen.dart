import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/providers.dart';
import '../core/mesh_service.dart';
import '../theme/app_theme.dart';

class CreateGroupScreen extends ConsumerStatefulWidget {
  const CreateGroupScreen({super.key});

  @override
  ConsumerState<CreateGroupScreen> createState() => _CreateGroupScreenState();
}

class _CreateGroupScreenState extends ConsumerState<CreateGroupScreen> {
  final TextEditingController _nameController = TextEditingController();
  final Set<String> _selectedPeers = {};
  bool _isLoading = false;

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  void _create() async {
    final name = _nameController.text.trim();
    if (name.isEmpty || _selectedPeers.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Nomi va a\'zolarni tanlang')),
      );
      return;
    }

    setState(() => _isLoading = true);
    try {
      await ref.read(meshServiceProvider).createGroup(name, _selectedPeers.toList());
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Xato: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final peersAsync = ref.watch(peersProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Guruh yaratish'),
        actions: [
          TextButton(
            onPressed: _isLoading ? null : _create,
            child: _isLoading
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                  )
                : Text(
                    'Yaratish',
                    style: TextStyle(color: MeshAppTheme.primary, fontWeight: FontWeight.bold),
                  ),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              controller: _nameController,
              style: const TextStyle(color: Colors.white),
              decoration: InputDecoration(
                hintText: 'Guruh nomi',
                hintStyle: TextStyle(color: MeshAppTheme.textDim),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: MeshAppTheme.border),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide(color: MeshAppTheme.border),
                ),
              ),
            ),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 16),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'A\'zolarni tanlang:',
                style: TextStyle(color: Colors.white70, fontSize: 14),
              ),
            ),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: peersAsync.when(
              data: (peers) {
                if (peers.isEmpty) {
                  return const Center(
                    child: Text(
                      'Hali peerlar yo\'q',
                      style: TextStyle(color: Colors.white54),
                    ),
                  );
                }
                return ListView.builder(
                  itemCount: peers.length,
                  itemBuilder: (context, index) {
                    final peer = peers[index];
                    final deviceId = peer['deviceId'] ?? '';
                    final displayName = peer['displayName'] ?? 'Noma\'lum';
                    final isSelected = _selectedPeers.contains(deviceId);

                    return ListTile(
                      leading: CircleAvatar(
                        backgroundColor: isSelected
                            ? MeshAppTheme.primary
                            : MeshAppTheme.bgElevated,
                        child: Icon(
                          isSelected ? Icons.check : Icons.person,
                          color: Colors.white,
                          size: 20,
                        ),
                      ),
                      title: Text(
                        displayName,
                        style: const TextStyle(color: Colors.white),
                      ),
                      subtitle: Text(
                        peer['online'] == true ? 'Online' : 'Offline',
                        style: TextStyle(
                          color: peer['online'] == true
                              ? MeshAppTheme.success
                              : MeshAppTheme.textDim,
                          fontSize: 12,
                        ),
                      ),
                      onTap: () {
                        setState(() {
                          if (isSelected) {
                            _selectedPeers.remove(deviceId);
                          } else {
                            _selectedPeers.add(deviceId);
                          }
                        });
                      },
                    );
                  },
                );
              },
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(child: Text('Xato: $e')),
            ),
          ),
        ],
      ),
    );
  }
}

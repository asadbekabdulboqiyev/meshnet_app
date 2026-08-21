import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:meshnet_app/core/mesh_service.dart';
import 'package:meshnet_app/theme/app_theme.dart';

class AdminView extends ConsumerStatefulWidget {
  const AdminView({super.key});

  @override
  ConsumerState<AdminView> createState() => _AdminViewState();
}

class _AdminViewState extends ConsumerState<AdminView> with SingleTickerProviderStateMixin {
  late TabController _tabController;
  List<Map<String, dynamic>> _devices = [];
  List<Map<String, dynamic>> _resources = [];
  final _deviceIdCtrl = TextEditingController();
  final _roleCtrl = TextEditingController();
  final _resourceTypeCtrl = TextEditingController();
  final _resourceIdCtrl = TextEditingController();
  final _targetDeviceCtrl = TextEditingController();
  final _permissionCtrl = TextEditingController();
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    _loadData();
  }

  @override
  void dispose() {
    _tabController.dispose();
    _deviceIdCtrl.dispose();
    _roleCtrl.dispose();
    _resourceTypeCtrl.dispose();
    _resourceIdCtrl.dispose();
    _targetDeviceCtrl.dispose();
    _permissionCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    setState(() => _loading = true);
    final identity = await ref.read(meshServiceProvider).getLocalIdentity();
    final myId = identity?['deviceId'] ?? '';
    final peers = await ref.read(meshServiceProvider).getPeers();
    if (mounted) {
      setState(() {
        _devices = [
          {'deviceId': myId, 'displayName': identity?['displayName'] ?? 'Me', 'isSelf': true},
          ...peers.map((p) => {...p, 'isSelf': false}),
        ];
        _loading = false;
      });
    }
  }

  Future<void> _setDeviceRole() async {
    if (_deviceIdCtrl.text.isEmpty || _roleCtrl.text.isEmpty) return;
    final ok = await ref.read(meshServiceProvider).setDeviceRole(_deviceIdCtrl.text, _roleCtrl.text);
    if (ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Role set'), behavior: SnackBarBehavior.floating));
      _deviceIdCtrl.clear(); _roleCtrl.clear();
    }
  }

  Future<void> _setResourceRole() async {
    if (_resourceTypeCtrl.text.isEmpty || _resourceIdCtrl.text.isEmpty || _targetDeviceCtrl.text.isEmpty || _roleCtrl.text.isEmpty) return;
    final ok = await ref.read(meshServiceProvider).setResourceRole(
      resourceType: _resourceTypeCtrl.text,
      resourceId: _resourceIdCtrl.text,
      deviceId: _targetDeviceCtrl.text,
      role: _roleCtrl.text,
    );
    if (ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Resource role set'), behavior: SnackBarBehavior.floating));
      _resourceTypeCtrl.clear(); _resourceIdCtrl.clear(); _targetDeviceCtrl.clear(); _roleCtrl.clear();
    }
  }

  Future<void> _checkPerm() async {
    if (_deviceIdCtrl.text.isEmpty || _permissionCtrl.text.isEmpty) return;
    final has = await ref.read(meshServiceProvider).checkPermission(
      deviceId: _deviceIdCtrl.text,
      permission: _permissionCtrl.text,
      resourceType: _resourceTypeCtrl.text.isEmpty ? null : _resourceTypeCtrl.text,
      resourceId: _resourceIdCtrl.text.isEmpty ? null : _resourceIdCtrl.text,
    );
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(has ? 'HAS PERMISSION' : 'NO PERMISSION'),
        behavior: SnackBarBehavior.floating,
        backgroundColor: has ? Colors.green : Colors.red,
      ));
    }
  }

  Future<void> _banDevice() async {
    if (_deviceIdCtrl.text.isEmpty) return;
    final ok = await ref.read(meshServiceProvider).banDevice(_deviceIdCtrl.text);
    if (ok && mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Banned'), behavior: SnackBarBehavior.floating));
  }

  Future<void> _unbanDevice() async {
    if (_deviceIdCtrl.text.isEmpty) return;
    final ok = await ref.read(meshServiceProvider).unbanDevice(_deviceIdCtrl.text);
    if (ok && mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Unbanned'), behavior: SnackBarBehavior.floating));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Admin & RBAC'),
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(text: 'Devices', icon: Icon(Icons.devices_rounded)),
            Tab(text: 'Resources', icon: Icon(Icons.folder_rounded)),
            Tab(text: 'Permissions', icon: Icon(Icons.security_rounded)),
          ],
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : TabBarView(
              controller: _tabController,
              children: [
                // Devices Tab
                ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    _buildDeviceRoleCard(),
                    const SizedBox(height: 16),
                    Text('Mesh Devices', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 8),
                    ..._devices.map((d) => _DeviceTile(device: d)),
                  ],
                ),
                // Resources Tab
                ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    _buildResourceRoleCard(),
                    const SizedBox(height: 16),
                    Text('Resource Roles (set per resource)', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 8),
                    _buildResourceTypeChips(),
                  ],
                ),
                // Permissions Tab
                ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    _buildPermissionCheckCard(),
                    const SizedBox(height: 16),
                    Text('Common Permissions', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 8),
                    _buildPermissionChips(),
                    const SizedBox(height: 16),
                    Text('Actions', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 8),
                    Row(children: [
                      Expanded(child: OutlinedButton.icon(icon: const Icon(Icons.block), label: const Text('Ban Device'), onPressed: _banDevice)),
                      const SizedBox(width: 8),
                      Expanded(child: OutlinedButton.icon(icon: const Icon(Icons.check_circle), label: const Text('Unban Device'), onPressed: _unbanDevice)),
                    ]),
                  ],
                ),
              ],
            ),
    );
  }

  Widget _buildDeviceRoleCard() {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Set Device Role', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 12),
            Row(children: [
              Expanded(child: DropdownMenu<String>(
                initialSelection: _devices.isNotEmpty ? _devices.first['deviceId'] : null,
                label: const Text('Device'),
                dropdownMenuEntries: _devices.map((d) => DropdownMenuEntry(value: d['deviceId'] as String, label: '${d['displayName']}${d['isSelf'] == true ? ' (You)' : ''}')).toList(),
                onSelected: (v) => _deviceIdCtrl.text = v ?? '',
              )),
              const SizedBox(width: 8),
              Expanded(child: DropdownMenu<String>(
                initialSelection: 'MEMBER',
                label: const Text('Role'),
                dropdownMenuEntries: const [
                  DropdownMenuEntry(value: 'OWNER', label: 'Owner'),
                  DropdownMenuEntry(value: 'ADMIN', label: 'Admin'),
                  DropdownMenuEntry(value: 'MODERATOR', label: 'Moderator'),
                  DropdownMenuEntry(value: 'MEMBER', label: 'Member'),
                  DropdownMenuEntry(value: 'GUEST', label: 'Guest'),
                  DropdownMenuEntry(value: 'BANNED', label: 'Banned'),
                ],
                onSelected: (v) => _roleCtrl.text = v ?? 'MEMBER',
              )),
            ]),
            const SizedBox(height: 8),
            FilledButton(onPressed: _setDeviceRole, child: const Text('Apply Role')),
          ],
        ),
      ),
    );
  }

  Widget _buildResourceRoleCard() {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Set Resource Role', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 12),
            Row(children: [
              Expanded(child: TextField(controller: _resourceTypeCtrl, decoration: const InputDecoration(labelText: 'Resource Type (file/board/doc/poll/app/gateway)', border: OutlineInputBorder()))),
              const SizedBox(width: 8),
              Expanded(child: TextField(controller: _resourceIdCtrl, decoration: const InputDecoration(labelText: 'Resource ID', border: OutlineInputBorder()))),
            ]),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: TextField(controller: _targetDeviceCtrl, decoration: const InputDecoration(labelText: 'Target Device ID', border: OutlineInputBorder()))),
              const SizedBox(width: 8),
              Expanded(child: DropdownMenu<String>(
                initialSelection: 'MEMBER',
                label: const Text('Role'),
                dropdownMenuEntries: const [
                  DropdownMenuEntry(value: 'OWNER', label: 'Owner'),
                  DropdownMenuEntry(value: 'ADMIN', label: 'Admin'),
                  DropdownMenuEntry(value: 'MODERATOR', label: 'Moderator'),
                  DropdownMenuEntry(value: 'MEMBER', label: 'Member'),
                  DropdownMenuEntry(value: 'GUEST', label: 'Guest'),
                ],
                onSelected: (v) => _roleCtrl.text = v ?? 'MEMBER',
              )),
            ]),
            const SizedBox(height: 8),
            FilledButton(onPressed: _setResourceRole, child: const Text('Apply Resource Role')),
          ],
        ),
      ),
    );
  }

  Widget _buildPermissionCheckCard() {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Check Permission', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 12),
            Row(children: [
              Expanded(child: TextField(controller: _deviceIdCtrl, decoration: const InputDecoration(labelText: 'Device ID', border: OutlineInputBorder()))),
              const SizedBox(width: 8),
              Expanded(child: DropdownMenu<String>(
                initialSelection: 'mesh.admin',
                label: const Text('Permission'),
                dropdownMenuEntries: const [
                  DropdownMenuEntry(value: 'mesh.admin', label: 'mesh.admin'),
                  DropdownMenuEntry(value: 'file.share', label: 'file.share'),
                  DropdownMenuEntry(value: 'file.download', label: 'file.download'),
                  DropdownMenuEntry(value: 'file.delete.any', label: 'file.delete.any'),
                  DropdownMenuEntry(value: 'board.admin', label: 'board.admin'),
                  DropdownMenuEntry(value: 'doc.admin', label: 'doc.admin'),
                  DropdownMenuEntry(value: 'poll.admin', label: 'poll.admin'),
                  DropdownMenuEntry(value: 'app.admin', label: 'app.admin'),
                  DropdownMenuEntry(value: 'gateway.admin', label: 'gateway.admin'),
                  DropdownMenuEntry(value: 'emergency.admin', label: 'emergency.admin'),
                  DropdownMenuEntry(value: 'search.admin', label: 'search.admin'),
                ],
                onSelected: (v) => _permissionCtrl.text = v ?? '',
              )),
            ]),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: TextField(controller: _resourceTypeCtrl, decoration: const InputDecoration(labelText: 'Resource Type (optional)', border: OutlineInputBorder()))),
              const SizedBox(width: 8),
              Expanded(child: TextField(controller: _resourceIdCtrl, decoration: const InputDecoration(labelText: 'Resource ID (optional)', border: OutlineInputBorder()))),
            ]),
            const SizedBox(height: 8),
            FilledButton(onPressed: _checkPerm, child: const Text('Check')),
          ],
        ),
      ),
    );
  }

  Widget _buildResourceTypeChips() {
    final types = ['file', 'board', 'doc', 'poll', 'app', 'gateway'];
    return Wrap(spacing: 8, children: types.map((t) => Chip(label: Text(t))).toList());
  }

  Widget _buildPermissionChips() {
    final perms = ['mesh.admin', 'file.share', 'file.download', 'board.draw', 'doc.edit', 'poll.vote', 'app.install', 'gateway.use', 'search.query'];
    return Wrap(spacing: 8, children: perms.map((p) => Chip(
      label: Text(p),
      onDeleted: () => { _permissionCtrl.text = p, _checkPerm() },
    )).toList());
  }
}

class _DeviceTile extends StatelessWidget {
  final Map<String, dynamic> device;

  const _DeviceTile({required this.device});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      elevation: 0,
      color: theme.colorScheme.surfaceContainerHighest,
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: device['isSelf'] == true ? theme.colorScheme.primaryContainer : theme.colorScheme.secondaryContainer,
          child: Icon(device['isSelf'] == true ? Icons.person : Icons.devices_rounded,
              color: device['isSelf'] == true ? theme.colorScheme.onPrimaryContainer : theme.colorScheme.onSecondaryContainer),
        ),
        title: Text(device['displayName'] ?? device['deviceId']?.substring(0, 8) ?? 'Unknown',
            style: TextStyle(fontWeight: device['isSelf'] == true ? FontWeight.w600 : FontWeight.normal)),
        subtitle: Text(device['deviceId']?.substring(0, 12) ?? '', style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'monospace')),
        trailing: device['isSelf'] == true
            ? Chip(label: Text('You', style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onPrimaryContainer)),
                backgroundColor: theme.colorScheme.primaryContainer)
            : null,
      ),
    );
  }
}
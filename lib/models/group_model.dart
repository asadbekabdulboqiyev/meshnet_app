class MeshGroup {
  final String groupId;
  final String name;
  final List<GroupMember> members;
  final DateTime createdAt;
  final String createdBy;

  const MeshGroup({
    required this.groupId,
    required this.name,
    required this.members,
    required this.createdAt,
    required this.createdBy,
  });

  factory MeshGroup.fromMap(Map<String, dynamic> map) {
    return MeshGroup(
      groupId: map['groupId'] ?? '',
      name: map['name'] ?? '',
      members: (map['members'] as List?)
              ?.map((m) => GroupMember.fromMap(m))
              .toList() ??
          [],
      createdAt: DateTime.fromMillisecondsSinceEpoch(map['createdAtMs'] ?? 0),
      createdBy: map['createdBy'] ?? '',
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'groupId': groupId,
      'name': name,
      'members': members.map((m) => m.toMap()).toList(),
      'createdAtMs': createdAt.millisecondsSinceEpoch,
      'createdBy': createdBy,
    };
  }
}

class GroupMember {
  final String deviceId;
  final String displayName;
  final String role;

  const GroupMember({
    required this.deviceId,
    required this.displayName,
    this.role = 'member',
  });

  factory GroupMember.fromMap(Map<String, dynamic> map) {
    return GroupMember(
      deviceId: map['deviceId'] ?? '',
      displayName: map['displayName'] ?? '',
      role: map['role'] ?? 'member',
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'deviceId': deviceId,
      'displayName': displayName,
      'role': role,
    };
  }
}

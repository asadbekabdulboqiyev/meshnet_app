import 'package:flutter_test/flutter_test.dart';
import 'package:meshnet_app/models/group_model.dart';

void main() {
  group('GroupMember constructor', () {
    test('creates with required fields and default role', () {
      final member = GroupMember(deviceId: 'dev1', displayName: 'Alice');
      expect(member.deviceId, 'dev1');
      expect(member.displayName, 'Alice');
      expect(member.role, 'member');
    });

    test('creates with custom role', () {
      final member = GroupMember(
        deviceId: 'dev1',
        displayName: 'Alice',
        role: 'admin',
      );
      expect(member.role, 'admin');
    });

    test('const constructor works', () {
      const m1 = GroupMember(deviceId: 'd', displayName: 'N');
      const m2 = GroupMember(deviceId: 'd', displayName: 'N');
      expect(identical(m1, m2), isTrue);
    });
  });

  group('GroupMember.fromMap', () {
    test('parses complete map', () {
      final map = {
        'deviceId': 'dev-abc',
        'displayName': 'Bob',
        'role': 'admin',
      };
      final member = GroupMember.fromMap(map);
      expect(member.deviceId, 'dev-abc');
      expect(member.displayName, 'Bob');
      expect(member.role, 'admin');
    });

    test('defaults missing fields', () {
      final member = GroupMember.fromMap({});
      expect(member.deviceId, '');
      expect(member.displayName, '');
      expect(member.role, 'member');
    });

    test('defaults only missing role', () {
      final map = {'deviceId': 'x', 'displayName': 'Y'};
      final member = GroupMember.fromMap(map);
      expect(member.deviceId, 'x');
      expect(member.displayName, 'Y');
      expect(member.role, 'member');
    });

    test('handles null values in map', () {
      final map = {'deviceId': null, 'displayName': null, 'role': null};
      final member = GroupMember.fromMap(map);
      expect(member.deviceId, '');
      expect(member.displayName, '');
      expect(member.role, 'member');
    });

    test('preserves custom role from map', () {
      final map = {
        'deviceId': 'd',
        'displayName': 'N',
        'role': 'moderator',
      };
      final member = GroupMember.fromMap(map);
      expect(member.role, 'moderator');
    });

    test('parses empty string role as-is', () {
      final map = {
        'deviceId': 'd',
        'displayName': 'N',
        'role': '',
      };
      final member = GroupMember.fromMap(map);
      expect(member.role, '');
    });
  });

  group('GroupMember.toMap', () {
    test('serializes correctly', () {
      final member = GroupMember(
        deviceId: 'dev1',
        displayName: 'Alice',
        role: 'admin',
      );
      final map = member.toMap();
      expect(map['deviceId'], 'dev1');
      expect(map['displayName'], 'Alice');
      expect(map['role'], 'admin');
    });

    test('includes default role', () {
      final member = GroupMember(deviceId: 'd', displayName: 'N');
      final map = member.toMap();
      expect(map['role'], 'member');
    });

    test('toMap returns exactly 3 keys', () {
      final member = GroupMember(deviceId: 'd', displayName: 'N');
      expect(member.toMap().length, 3);
    });
  });

  group('GroupMember fromMap/toMap roundtrip', () {
    test('roundtrip preserves all fields', () {
      final original = GroupMember(
        deviceId: 'dev-123',
        displayName: 'Charlie',
        role: 'admin',
      );
      final restored = GroupMember.fromMap(original.toMap());
      expect(restored.deviceId, original.deviceId);
      expect(restored.displayName, original.displayName);
      expect(restored.role, original.role);
    });

    test('roundtrip with default role', () {
      final original = GroupMember(deviceId: 'd', displayName: 'N');
      final restored = GroupMember.fromMap(original.toMap());
      expect(restored.role, 'member');
    });

    test('roundtrip with empty strings', () {
      final original = GroupMember(deviceId: '', displayName: '', role: '');
      final restored = GroupMember.fromMap(original.toMap());
      expect(restored.deviceId, '');
      expect(restored.displayName, '');
      expect(restored.role, '');
    });

    test('roundtrip with special characters', () {
      final original = GroupMember(
        deviceId: 'dev-12345-abcde',
        displayName: "O'tkan chiziq & maxsus belgilar <tag>",
        role: 'admin',
      );
      final restored = GroupMember.fromMap(original.toMap());
      expect(restored.deviceId, original.deviceId);
      expect(restored.displayName, original.displayName);
      expect(restored.role, original.role);
    });

    test('roundtrip with long strings', () {
      final longName = 'A' * 500;
      final original = GroupMember(
        deviceId: 'dev',
        displayName: longName,
      );
      final restored = GroupMember.fromMap(original.toMap());
      expect(restored.displayName, longName);
    });

    test('roundtrip with unicode characters', () {
      final original = GroupMember(
        deviceId: 'dev',
        displayName: 'Yangi foydalanuvchi',
      );
      final restored = GroupMember.fromMap(original.toMap());
      expect(restored.displayName, 'Yangi foydalanuvchi');
    });
  });

  group('MeshGroup constructor', () {
    test('creates with all required fields', () {
      final now = DateTime(2025, 1, 1);
      final group = MeshGroup(
        groupId: 'g1',
        name: 'Test Group',
        members: [],
        createdAt: now,
        createdBy: 'creator-1',
      );
      expect(group.groupId, 'g1');
      expect(group.name, 'Test Group');
      expect(group.members, isEmpty);
      expect(group.createdAt, now);
      expect(group.createdBy, 'creator-1');
    });

    test('creates with members', () {
      final members = [
        GroupMember(deviceId: 'd1', displayName: 'A', role: 'admin'),
        GroupMember(deviceId: 'd2', displayName: 'B'),
      ];
      final group = MeshGroup(
        groupId: 'g',
        name: 'Gr',
        members: members,
        createdAt: DateTime(2025),
        createdBy: 'd1',
      );
      expect(group.members.length, 2);
    });

    test('constructor works', () {
      final g1 = MeshGroup(
        groupId: 'g',
        name: 'N',
        members: [],
        createdAt: DateTime.fromMillisecondsSinceEpoch(0),
        createdBy: 'c',
      );
      expect(g1.groupId, 'g');
    });
  });

  group('MeshGroup.fromMap', () {
    test('parses complete map', () {
      final map = {
        'groupId': 'grp-001',
        'name': 'Dev Team',
        'members': [
          {'deviceId': 'd1', 'displayName': 'Alice', 'role': 'admin'},
          {'deviceId': 'd2', 'displayName': 'Bob', 'role': 'member'},
        ],
        'createdAtMs': 1700000000000,
        'createdBy': 'd1',
      };
      final group = MeshGroup.fromMap(map);
      expect(group.groupId, 'grp-001');
      expect(group.name, 'Dev Team');
      expect(group.members.length, 2);
      expect(group.members[0].deviceId, 'd1');
      expect(group.members[0].role, 'admin');
      expect(group.members[1].displayName, 'Bob');
      expect(group.createdAt, DateTime.fromMillisecondsSinceEpoch(1700000000000));
      expect(group.createdBy, 'd1');
    });

    test('defaults missing fields', () {
      final group = MeshGroup.fromMap({});
      expect(group.groupId, '');
      expect(group.name, '');
      expect(group.members, isEmpty);
      expect(group.createdAt, DateTime.fromMillisecondsSinceEpoch(0));
      expect(group.createdBy, '');
    });

    test('defaults missing createdAtMs to epoch', () {
      final group = MeshGroup.fromMap({'groupId': 'g'});
      expect(group.createdAt, DateTime.fromMillisecondsSinceEpoch(0));
    });

    test('handles null members list', () {
      final group = MeshGroup.fromMap({
        'groupId': 'g',
        'members': null,
      });
      expect(group.members, isEmpty);
    });

    test('handles empty members list', () {
      final group = MeshGroup.fromMap({
        'groupId': 'g',
        'members': [],
      });
      expect(group.members, isEmpty);
    });

    test('parses multiple members', () {
      final members = List.generate(10, (i) => {
        'deviceId': 'dev$i',
        'displayName': 'User$i',
        'role': 'member',
      });
      final group = MeshGroup.fromMap({
        'groupId': 'g',
        'members': members,
      });
      expect(group.members.length, 10);
      for (int i = 0; i < 10; i++) {
        expect(group.members[i].deviceId, 'dev$i');
        expect(group.members[i].displayName, 'User$i');
      }
    });

    test('handles null member fields gracefully', () {
      final group = MeshGroup.fromMap({
        'groupId': 'g',
        'members': [
          {'deviceId': null, 'displayName': null},
        ],
      });
      expect(group.members.length, 1);
      expect(group.members[0].deviceId, '');
      expect(group.members[0].displayName, '');
    });

    test('preserves negative timestamp', () {
      final group = MeshGroup.fromMap({
        'createdAtMs': -1,
      });
      expect(group.createdAt.millisecondsSinceEpoch, -1);
    });

    test('preserves large timestamp', () {
      final largeTs = 4102444800000; // ~2100
      final group = MeshGroup.fromMap({'createdAtMs': largeTs});
      expect(group.createdAt.millisecondsSinceEpoch, largeTs);
    });
  });

  group('MeshGroup.toMap', () {
    test('serializes correctly', () {
      final group = MeshGroup(
        groupId: 'g1',
        name: 'Test',
        members: [
          GroupMember(deviceId: 'd1', displayName: 'A'),
        ],
        createdAt: DateTime.fromMillisecondsSinceEpoch(1700000000000),
        createdBy: 'd1',
      );
      final map = group.toMap();
      expect(map['groupId'], 'g1');
      expect(map['name'], 'Test');
      expect(map['createdBy'], 'd1');
      expect(map['createdAtMs'], 1700000000000);
      expect((map['members'] as List).length, 1);
    });

    test('toMap returns exactly 5 keys', () {
      final group = MeshGroup(
        groupId: 'g',
        name: 'N',
        members: [],
        createdAt: DateTime(2025),
        createdBy: 'c',
      );
      expect(group.toMap().length, 5);
    });

    test('serializes members as maps', () {
      final group = MeshGroup(
        groupId: 'g',
        name: 'N',
        members: [
          GroupMember(deviceId: 'd', displayName: 'N', role: 'admin'),
        ],
        createdAt: DateTime(2025),
        createdBy: 'c',
      );
      final membersList = group.toMap()['members'] as List;
      expect(membersList.first, isA<Map>());
      expect(membersList.first['deviceId'], 'd');
    });

    test('empty members list serializes as empty list', () {
      final group = MeshGroup(
        groupId: 'g',
        name: 'N',
        members: [],
        createdAt: DateTime(2025),
        createdBy: 'c',
      );
      expect(group.toMap()['members'], isEmpty);
    });
  });

  group('MeshGroup fromMap/toMap roundtrip', () {
    test('roundtrip preserves all fields', () {
      final original = MeshGroup(
        groupId: 'grp-abc',
        name: 'Engineering',
        members: [
          GroupMember(deviceId: 'd1', displayName: 'Alice', role: 'admin'),
          GroupMember(deviceId: 'd2', displayName: 'Bob'),
          GroupMember(deviceId: 'd3', displayName: 'Charlie', role: 'moderator'),
        ],
        createdAt: DateTime.fromMillisecondsSinceEpoch(1700000000000),
        createdBy: 'd1',
      );
      final restored = MeshGroup.fromMap(original.toMap());
      expect(restored.groupId, original.groupId);
      expect(restored.name, original.name);
      expect(restored.members.length, original.members.length);
      expect(restored.createdAt, original.createdAt);
      expect(restored.createdBy, original.createdBy);
      for (int i = 0; i < original.members.length; i++) {
        expect(restored.members[i].deviceId, original.members[i].deviceId);
        expect(restored.members[i].displayName, original.members[i].displayName);
        expect(restored.members[i].role, original.members[i].role);
      }
    });

    test('roundtrip with empty group', () {
      final original = MeshGroup(
        groupId: 'g',
        name: 'Empty',
        members: [],
        createdAt: DateTime(2025),
        createdBy: 'c',
      );
      final restored = MeshGroup.fromMap(original.toMap());
      expect(restored.groupId, 'g');
      expect(restored.members, isEmpty);
    });

    test('roundtrip preserves special characters', () {
      final original = MeshGroup(
        groupId: 'g',
        name: "O'zbekiston guruhi",
        members: [
          GroupMember(deviceId: 'd', displayName: 'Foydalanuvchi'),
        ],
        createdAt: DateTime.fromMillisecondsSinceEpoch(1700000000000),
        createdBy: 'd',
      );
      final restored = MeshGroup.fromMap(original.toMap());
      expect(restored.name, "O'zbekiston guruhi");
      expect(restored.members[0].displayName, 'Foydalanuvchi');
    });

    test('roundtrip with many members', () {
      final members = List.generate(
        50,
        (i) => GroupMember(
          deviceId: 'device-$i',
          displayName: 'User $i',
          role: i == 0 ? 'admin' : 'member',
        ),
      );
      final original = MeshGroup(
        groupId: 'big-group',
        name: 'Big Group',
        members: members,
        createdAt: DateTime.fromMillisecondsSinceEpoch(1700000000000),
        createdBy: 'device-0',
      );
      final restored = MeshGroup.fromMap(original.toMap());
      expect(restored.members.length, 50);
      expect(restored.members[0].role, 'admin');
      expect(restored.members[49].deviceId, 'device-49');
    });

    test('double roundtrip is stable', () {
      final original = MeshGroup(
        groupId: 'g',
        name: 'N',
        members: [GroupMember(deviceId: 'd', displayName: 'A')],
        createdAt: DateTime.fromMillisecondsSinceEpoch(1700000000000),
        createdBy: 'd',
      );
      final first = MeshGroup.fromMap(original.toMap());
      final second = MeshGroup.fromMap(first.toMap());
      expect(second.toMap(), first.toMap());
    });
  });

  group('MeshGroup identity', () {
    test('two identical groups have equal fields', () {
      final g1 = MeshGroup(
        groupId: 'g',
        name: 'N',
        members: [],
        createdAt: DateTime(2025),
        createdBy: 'c',
      );
      final g2 = MeshGroup(
        groupId: 'g',
        name: 'N',
        members: [],
        createdAt: DateTime(2025),
        createdBy: 'c',
      );
      expect(g1.groupId, g2.groupId);
      expect(g1.name, g2.name);
      expect(g1.createdBy, g2.createdBy);
    });

    test('different groupId means not equal', () {
      final g1 = MeshGroup(
        groupId: 'g1',
        name: 'N',
        members: [],
        createdAt: DateTime(2025),
        createdBy: 'c',
      );
      final g2 = MeshGroup(
        groupId: 'g2',
        name: 'N',
        members: [],
        createdAt: DateTime(2025),
        createdBy: 'c',
      );
      expect(g1, isNot(equals(g2)));
    });
  });
}

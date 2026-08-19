import 'package:flutter_test/flutter_test.dart';
import 'package:meshnet_app/models/message_model.dart';

void main() {
  group('MessageType enum', () {
    test('has all expected values', () {
      expect(MessageType.values.length, 5);
      expect(MessageType.text, isNotNull);
      expect(MessageType.image, isNotNull);
      expect(MessageType.file, isNotNull);
      expect(MessageType.voice, isNotNull);
      expect(MessageType.groupText, isNotNull);
    });

    test('enum values are distinct', () {
      final values = MessageType.values;
      final names = values.map((e) => e.name).toSet();
      expect(names.length, values.length);
    });

    test('enum index is sequential', () {
      expect(MessageType.text.index, 0);
      expect(MessageType.image.index, 1);
      expect(MessageType.file.index, 2);
      expect(MessageType.voice.index, 3);
      expect(MessageType.groupText.index, 4);
    });

    test('can iterate all values', () {
      final collected = <MessageType>[];
      for (final type in MessageType.values) {
        collected.add(type);
      }
      expect(collected, MessageType.values);
    });

    test('name property returns lowercase', () {
      expect(MessageType.text.name, 'text');
      expect(MessageType.image.name, 'image');
      expect(MessageType.file.name, 'file');
      expect(MessageType.voice.name, 'voice');
      expect(MessageType.groupText.name, 'groupText');
    });
  });

  group('MessageStatus enum', () {
    test('has all expected values', () {
      expect(MessageStatus.values.length, 7);
    });

    test('enum values are distinct', () {
      final values = MessageStatus.values;
      final names = values.map((e) => e.name).toSet();
      expect(names.length, values.length);
    });

    test('enum index is sequential', () {
      expect(MessageStatus.sending.index, 0);
      expect(MessageStatus.pending.index, 1);
      expect(MessageStatus.sent.index, 2);
      expect(MessageStatus.delivered.index, 3);
      expect(MessageStatus.read.index, 4);
      expect(MessageStatus.failed.index, 5);
      expect(MessageStatus.transferring.index, 6);
    });

    test('name property returns correct values', () {
      expect(MessageStatus.sending.name, 'sending');
      expect(MessageStatus.pending.name, 'pending');
      expect(MessageStatus.sent.name, 'sent');
      expect(MessageStatus.delivered.name, 'delivered');
      expect(MessageStatus.read.name, 'read');
      expect(MessageStatus.failed.name, 'failed');
      expect(MessageStatus.transferring.name, 'transferring');
    });
  });

  group('ChatMessage constructor', () {
    test('creates with required fields only', () {
      final msg = ChatMessage(messageId: 'id1', fromMe: true);
      expect(msg.messageId, 'id1');
      expect(msg.fromMe, isTrue);
      expect(msg.text, '');
      expect(msg.type, MessageType.text);
      expect(msg.status, MessageStatus.sent);
      expect(msg.timestamp, isNull);
      expect(msg.localPath, isNull);
      expect(msg.remotePath, isNull);
      expect(msg.fileName, isNull);
      expect(msg.fileSize, isNull);
      expect(msg.mimeType, isNull);
      expect(msg.transferProgress, isNull);
      expect(msg.audioDuration, isNull);
      expect(msg.groupName, isNull);
      expect(msg.senderName, isNull);
      expect(msg.fromDeviceId, isNull);
    });

    test('creates with all fields', () {
      final now = DateTime(2025, 6, 15, 12, 30);
      final msg = ChatMessage(
        messageId: 'id2',
        text: 'hello',
        type: MessageType.image,
        fromMe: false,
        fromDeviceId: 'device-abc',
        status: MessageStatus.delivered,
        timestamp: now,
        localPath: '/tmp/img.jpg',
        remotePath: 'remote/img.jpg',
        fileName: 'img.jpg',
        fileSize: 2048,
        mimeType: 'image/jpeg',
        transferProgress: 0.75,
        audioDuration: Duration(seconds: 45),
        groupName: 'MyGroup',
        senderName: 'Alice',
      );

      expect(msg.messageId, 'id2');
      expect(msg.text, 'hello');
      expect(msg.type, MessageType.image);
      expect(msg.fromMe, isFalse);
      expect(msg.fromDeviceId, 'device-abc');
      expect(msg.status, MessageStatus.delivered);
      expect(msg.timestamp, now);
      expect(msg.localPath, '/tmp/img.jpg');
      expect(msg.remotePath, 'remote/img.jpg');
      expect(msg.fileName, 'img.jpg');
      expect(msg.fileSize, 2048);
      expect(msg.mimeType, 'image/jpeg');
      expect(msg.transferProgress, 0.75);
      expect(msg.audioDuration, Duration(seconds: 45));
      expect(msg.groupName, 'MyGroup');
      expect(msg.senderName, 'Alice');
    });

    test('fromMe false creates correct message', () {
      final msg = ChatMessage(messageId: 'id3', fromMe: false);
      expect(msg.fromMe, isFalse);
    });

    test('const constructor allows identical instances', () {
      const msg1 = ChatMessage(messageId: 'id', fromMe: true);
      const msg2 = ChatMessage(messageId: 'id', fromMe: true);
      expect(identical(msg1, msg2), isTrue);
    });

    test('different const messages are distinct', () {
      const msg1 = ChatMessage(messageId: 'id1', fromMe: true);
      const msg2 = ChatMessage(messageId: 'id2', fromMe: true);
      expect(identical(msg1, msg2), isFalse);
    });
  });

  group('ChatMessage.copyWith', () {
    test('copies with no changes produces equal message', () {
      final original = ChatMessage(
        messageId: 'id1',
        text: 'hello',
        type: MessageType.voice,
        fromMe: true,
        status: MessageStatus.failed,
        timestamp: DateTime(2025),
        localPath: '/local',
        remotePath: 'remote',
        fileName: 'file.txt',
        fileSize: 1024,
        mimeType: 'text/plain',
        transferProgress: 0.5,
        audioDuration: Duration(seconds: 10),
      );

      final copy = original.copyWith();
      expect(copy.messageId, original.messageId);
      expect(copy.text, original.text);
      expect(copy.type, original.type);
      expect(copy.fromMe, original.fromMe);
      expect(copy.status, original.status);
      expect(copy.timestamp, original.timestamp);
      expect(copy.localPath, original.localPath);
      expect(copy.remotePath, original.remotePath);
      expect(copy.transferProgress, original.transferProgress);
      expect(copy.audioDuration, original.audioDuration);
    });

    test('copyWith overrides text', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true, text: 'old');
      final copy = msg.copyWith(text: 'new');
      expect(copy.text, 'new');
      expect(copy.messageId, 'id');
    });

    test('copyWith overrides messageId', () {
      final msg = ChatMessage(messageId: 'old_id', fromMe: true);
      final copy = msg.copyWith(messageId: 'new_id');
      expect(copy.messageId, 'new_id');
    });

    test('copyWith overrides type', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      final copy = msg.copyWith(type: MessageType.file);
      expect(copy.type, MessageType.file);
    });

    test('copyWith overrides status', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      final copy = msg.copyWith(status: MessageStatus.failed);
      expect(copy.status, MessageStatus.failed);
    });

    test('copyWith overrides timestamp', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      final ts = DateTime(2030);
      final copy = msg.copyWith(timestamp: ts);
      expect(copy.timestamp, ts);
    });

    test('copyWith overrides localPath', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      final copy = msg.copyWith(localPath: '/new/path');
      expect(copy.localPath, '/new/path');
    });

    test('copyWith overrides remotePath', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      final copy = msg.copyWith(remotePath: 'new/remote');
      expect(copy.remotePath, 'new/remote');
    });

    test('copyWith overrides transferProgress', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      final copy = msg.copyWith(transferProgress: 0.99);
      expect(copy.transferProgress, 0.99);
    });

    test('copyWith overrides audioDuration', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      final copy = msg.copyWith(audioDuration: Duration(minutes: 5));
      expect(copy.audioDuration, Duration(minutes: 5));
    });

    test('copyWith preserves fromMe (immutable)', () {
      final msg = ChatMessage(messageId: 'id', fromMe: false);
      final copy = msg.copyWith(text: 'changed');
      expect(copy.fromMe, isFalse);
    });

    test('copyWith preserves fromDeviceId (immutable)', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        fromDeviceId: 'device-xyz',
      );
      final copy = msg.copyWith(text: 'new text');
      expect(copy.fromDeviceId, 'device-xyz');
    });

    test('copyWith preserves fileName (immutable)', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        fileName: 'doc.pdf',
      );
      final copy = msg.copyWith(text: 'x');
      expect(copy.fileName, 'doc.pdf');
    });

    test('copyWith preserves fileSize (immutable)', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        fileSize: 4096,
      );
      final copy = msg.copyWith(text: 'x');
      expect(copy.fileSize, 4096);
    });

    test('copyWith preserves mimeType (immutable)', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        mimeType: 'audio/ogg',
      );
      final copy = msg.copyWith(text: 'x');
      expect(copy.mimeType, 'audio/ogg');
    });

    test('copyWith preserves groupName (immutable)', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        groupName: 'DevTeam',
      );
      final copy = msg.copyWith(text: 'x');
      expect(copy.groupName, 'DevTeam');
    });

    test('copyWith preserves senderName (immutable)', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        senderName: 'Bob',
      );
      final copy = msg.copyWith(text: 'x');
      expect(copy.senderName, 'Bob');
    });

    test('chained copyWith works correctly', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true, text: 'a');
      final copy = msg.copyWith(
        text: 'b',
        type: MessageType.image,
        status: MessageStatus.sending,
      );
      expect(copy.text, 'b');
      expect(copy.type, MessageType.image);
      expect(copy.status, MessageStatus.sending);
      expect(copy.messageId, 'id');
    });

    test('copyWith does not create identity', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true, text: 'old');
      final copy = msg.copyWith(text: 'new');
      expect(identical(msg, copy), isFalse);
    });
  });

  group('ChatMessage.displaySize', () {
    test('returns empty string when fileSize is null', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      expect(msg.displaySize, '');
    });

    test('returns bytes for files < 1024', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true, fileSize: 0);
      expect(msg.displaySize, '0 B');

      final msg2 = ChatMessage(messageId: 'id', fromMe: true, fileSize: 1);
      expect(msg2.displaySize, '1 B');

      final msg3 = ChatMessage(messageId: 'id', fromMe: true, fileSize: 512);
      expect(msg3.displaySize, '512 B');

      final msg4 = ChatMessage(messageId: 'id', fromMe: true, fileSize: 1023);
      expect(msg4.displaySize, '1023 B');
    });

    test('returns KB for files 1024 to 1048575', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true, fileSize: 1024);
      expect(msg.displaySize, '1.0 KB');

      final msg2 = ChatMessage(messageId: 'id', fromMe: true, fileSize: 1536);
      expect(msg2.displaySize, '1.5 KB');

      final msg3 = ChatMessage(messageId: 'id', fromMe: true, fileSize: 10240);
      expect(msg3.displaySize, '10.0 KB');

      final msg4 = ChatMessage(messageId: 'id', fromMe: true, fileSize: 1048575);
      expect(msg4.displaySize, '1024.0 KB');
    });

    test('returns MB for files >= 1048576', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        fileSize: 1048576,
      );
      expect(msg.displaySize, '1.0 MB');

      final msg2 = ChatMessage(
        messageId: 'id',
        fromMe: true,
        fileSize: 5242880,
      );
      expect(msg2.displaySize, '5.0 MB');

      final msg3 = ChatMessage(
        messageId: 'id',
        fromMe: true,
        fileSize: 1073741824,
      );
      expect(msg3.displaySize, '1024.0 MB');
    });

    test('boundary: exactly 1024 shows KB', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true, fileSize: 1024);
      expect(msg.displaySize, '1.0 KB');
    });

    test('boundary: 1023 shows bytes', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true, fileSize: 1023);
      expect(msg.displaySize, '1023 B');
    });

    test('boundary: exactly 1048576 shows MB', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        fileSize: 1048576,
      );
      expect(msg.displaySize, '1.0 MB');
    });

    test('boundary: 1048575 shows KB', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        fileSize: 1048575,
      );
      expect(msg.displaySize, '1024.0 KB');
    });
  });

  group('ChatMessage.displayDuration', () {
    test('returns 0:00 when audioDuration is null', () {
      final msg = ChatMessage(messageId: 'id', fromMe: true);
      expect(msg.displayDuration, '0:00');
    });

    test('formats zero duration', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration.zero,
      );
      expect(msg.displayDuration, '0:00');
    });

    test('formats seconds only', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration(seconds: 5),
      );
      expect(msg.displayDuration, '0:05');
    });

    test('formats seconds with leading zero', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration(seconds: 9),
      );
      expect(msg.displayDuration, '0:09');
    });

    test('formats minutes and seconds', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration(minutes: 3, seconds: 30),
      );
      expect(msg.displayDuration, '3:30');
    });

    test('formats exactly one minute', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration(minutes: 1, seconds: 0),
      );
      expect(msg.displayDuration, '1:00');
    });

    test('formats large duration', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration(minutes: 59, seconds: 59),
      );
      expect(msg.displayDuration, '59:59');
    });

    test('formats hour+ as accumulated minutes', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration(minutes: 125, seconds: 30),
      );
      expect(msg.displayDuration, '125:30');
    });

    test('formats sub-second duration as 0:00', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration(milliseconds: 500),
      );
      expect(msg.displayDuration, '0:00');
    });

    test('formats 10 seconds', () {
      final msg = ChatMessage(
        messageId: 'id',
        fromMe: true,
        audioDuration: Duration(seconds: 10),
      );
      expect(msg.displayDuration, '0:10');
    });
  });

  group('ChatMessage equality', () {
    test('two messages with same fields have equal fields', () {
      final msg1 = ChatMessage(messageId: 'id', fromMe: true, text: 'hi');
      final msg2 = ChatMessage(messageId: 'id', fromMe: true, text: 'hi');
      expect(msg1.messageId, msg2.messageId);
      expect(msg1.fromMe, msg2.fromMe);
      expect(msg1.text, msg2.text);
    });

    test('two messages with different IDs have different messageId', () {
      final msg1 = ChatMessage(messageId: 'id1', fromMe: true);
      final msg2 = ChatMessage(messageId: 'id2', fromMe: true);
      expect(msg1.messageId, isNot(msg2.messageId));
    });

    test('two messages with different text have different text', () {
      final msg1 = ChatMessage(messageId: 'id', fromMe: true, text: 'a');
      final msg2 = ChatMessage(messageId: 'id', fromMe: true, text: 'b');
      expect(msg1.text, isNot(msg2.text));
    });

    test('two messages with different fromMe have different fromMe', () {
      final msg1 = ChatMessage(messageId: 'id', fromMe: true);
      final msg2 = ChatMessage(messageId: 'id', fromMe: false);
      expect(msg1.fromMe, isNot(msg2.fromMe));
    });
  });
}

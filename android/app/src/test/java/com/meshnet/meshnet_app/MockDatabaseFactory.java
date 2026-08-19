package com.meshnet.meshnet_app;

import com.meshnet.meshnet_app.crypto.RatchetSessionStore;
import com.meshnet.meshnet_app.protocol.GroupStore;
import com.meshnet.meshnet_app.storage.MeshDatabase;
import com.meshnet.meshnet_app.storage.MessageStore;
import com.meshnet.meshnet_app.storage.PeerStore;

import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Java helper for mock MeshDatabase creation.
 * Java is used because Kotlin's null-safety checks on non-nullable parameters
 * cause NPEs when Mockito matchers return null at the call site.
 */
public class MockDatabaseFactory {

    public static MeshDatabase createMockDatabase() {
        Map<String, PeerStore.Peer> peers = new HashMap<>();
        List<MessageStore.IncomingMessage> incomingMessages = new ArrayList<>();
        List<MessageStore.OutboxMessage> outboxMessages = new ArrayList<>();
        Map<String, GroupStore.Group> groups = new HashMap<>();
        Map<String, List<GroupStore.GroupMember>> groupMembers = new HashMap<>();
        Map<String, RatchetSessionStore.SessionInfo> ratchetSessionInfos = new HashMap<>();
        Map<String, String> identity = new HashMap<>();

        MeshDatabase db = mock(MeshDatabase.class);

        // =================== Peer ops ===================

        doAnswer(inv -> new ArrayList<>(peers.values())).when(db).getAllPeers();

        doAnswer(inv -> {
            String id = inv.getArgument(0);
            return peers.get(id);
        }).when(db).getPeer(any());

        doAnswer(inv -> {
            PeerStore.Peer peer = inv.getArgument(0);
            peers.put(peer.getDeviceId(), peer);
            return null;
        }).when(db).upsertPeer(any());

        doAnswer(inv -> {
            String id = inv.getArgument(0);
            peers.remove(id);
            return null;
        }).when(db).removePeer(any());

        // =================== Incoming message ops ===================

        doAnswer(inv -> {
            MessageStore.IncomingMessage msg = inv.getArgument(0);
            incomingMessages.removeIf(m -> m.getMessageId().equals(msg.getMessageId()));
            incomingMessages.add(0, msg);
            return null;
        }).when(db).addIncomingMessage(any());

        doAnswer(inv -> new ArrayList<>(incomingMessages)).when(db).loadIncomingMessages();

        doAnswer(inv -> {
            String fromId = inv.getArgument(0);
            long count = 0;
            for (MessageStore.IncomingMessage m : incomingMessages) {
                if (m.getFromDeviceId().equals(fromId) && !m.isRead()) count++;
            }
            return (int) count;
        }).when(db).getUnreadCount(any());

        doAnswer(inv -> {
            long count = 0;
            for (MessageStore.IncomingMessage m : incomingMessages) {
                if (!m.isRead()) count++;
            }
            return (int) count;
        }).when(db).getTotalUnreadCount();

        doAnswer(inv -> {
            String fromId = inv.getArgument(0);
            long now = System.currentTimeMillis();
            int count = 0;
            for (int i = 0; i < incomingMessages.size(); i++) {
                MessageStore.IncomingMessage msg = incomingMessages.get(i);
                if (msg.getFromDeviceId().equals(fromId) && !msg.isRead()) {
                    incomingMessages.set(i, new MessageStore.IncomingMessage(
                            msg.getMessageId(), msg.getFromDeviceId(), msg.getMessage(),
                            msg.getReceivedAtMs(), true, now));
                    count++;
                }
            }
            return count;
        }).when(db).markMessagesRead(any());

        doAnswer(inv -> {
            while (incomingMessages.size() > 200) {
                incomingMessages.remove(incomingMessages.size() - 1);
            }
            return null;
        }).when(db).deleteOldIncomingMessages(200);

        // =================== Outbox ops ===================

        doAnswer(inv -> {
            outboxMessages.clear();
            outboxMessages.addAll(inv.getArgument(0));
            return null;
        }).when(db).saveOutbox(any());

        doAnswer(inv -> new ArrayList<>(outboxMessages)).when(db).loadOutbox();

        // =================== Group ops ===================

        doAnswer(inv -> {
            GroupStore.Group group = inv.getArgument(0);
            groups.put(group.getGroupId(), group);
            groupMembers.put(group.getGroupId(), new ArrayList<>(group.getMembers()));
            return null;
        }).when(db).insertGroup(any());

        doAnswer(inv -> {
            String id = inv.getArgument(0);
            return groups.get(id);
        }).when(db).getGroup(any());

        doAnswer(inv -> new ArrayList<>(groups.values())).when(db).getAllGroups();

        doAnswer(inv -> {
            String id = inv.getArgument(0);
            groups.remove(id);
            groupMembers.remove(id);
            return null;
        }).when(db).deleteGroup(any());

        doAnswer(inv -> {
            String id = inv.getArgument(0);
            List<GroupStore.GroupMember> members = groupMembers.get(id);
            if (members == null) return new ArrayList<>();
            return new ArrayList<>(members);
        }).when(db).getGroupMembers(any());

        // =================== Ratchet session ops ===================

        doAnswer(inv -> {
            String peerId = inv.getArgument(0);
            RatchetSessionStore.SessionInfo info = inv.getArgument(1);
            ratchetSessionInfos.put(peerId, info);
            return null;
        }).when(db).saveRatchetSession(any(), any());

        doAnswer(inv -> {
            String peerId = inv.getArgument(0);
            return ratchetSessionInfos.get(peerId);
        }).when(db).getRatchetSessionInfo(any());

        doAnswer(inv -> {
            String peerId = inv.getArgument(0);
            ratchetSessionInfos.remove(peerId);
            return null;
        }).when(db).removeRatchetSession(any());

        doAnswer(inv -> {
            String peerId = inv.getArgument(0);
            return ratchetSessionInfos.containsKey(peerId);
        }).when(db).hasRatchetSession(any());

        doAnswer(inv -> new HashSet<>(ratchetSessionInfos.keySet())).when(db).getAllRatchetSessionIds();

        // =================== Identity ops ===================

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            identity.put(key, value);
            return null;
        }).when(db).setIdentity(any(), any());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            return identity.get(key);
        }).when(db).getIdentity(any());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            return identity.containsKey(key);
        }).when(db).hasIdentity(any());

        return db;
    }
}

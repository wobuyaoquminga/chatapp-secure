package com.example.chatandroid;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Prepared off the UI thread. Reuses unchanged snapshots and indexes append deltas only. */
final class MessageIndex {
    final Map<String, List<JSONObject>> history = new HashMap<>();
    final Map<String, List<String>> rowKeys = new HashMap<>();
    final Map<String, JSONObject> latest = new HashMap<>();
    final List<String> conversations = new ArrayList<>();
    final List<String> contacts = new ArrayList<>();
    final Map<String, Map<Integer, LocationCard>> locationCards = new HashMap<>();
    final Map<String, java.util.Set<Integer>> hiddenLocations = new HashMap<>();
    final Map<String, Map<String, LocationCard>> sessions = new HashMap<>();
    String scope = "";
    long revision = -1;
    static final class LocationCard {
        LocationPayload latest, coordinate;
        int anchor;
        boolean stopped;
        long sessionExpires;
        String key() { return latest.kind + ":" + latest.seq + ":" + latest.recordedAt + ":" + stopped + ":" + sessionExpires; }
    }
    LocationCard card(String peer, int index) {
        Map<Integer, LocationCard> cards = locationCards.get(peer);
        return cards == null ? null : cards.get(index);
    }
    boolean hidden(String peer, int index) {
        java.util.Set<Integer> hidden = hiddenLocations.get(peer);
        return hidden != null && hidden.contains(index);
    }
    String messageRows = "", contactRows = "";

    static MessageIndex build(JSONObject state) { return build(state, null); }
    static MessageIndex build(JSONObject state, MessageIndex previous) {
        MessageIndex index = new MessageIndex();
        index.scope = state.optString("server") + "\0" + state.optString("username");
        index.revision = state.optLong("messagesRevision", -1);
        boolean reuse = previous != null && index.revision >= 0 && index.revision == previous.revision
                && index.scope.equals(previous.scope);
        int appendFrom = state.optInt("messagesAppendFrom", -1);
        boolean append = previous != null && !reuse && appendFrom >= 0
                && previous.revision == state.optLong("messagesBaseRevision", -2) && index.scope.equals(previous.scope);
        if (reuse || append) {
            index.history.putAll(previous.history); index.latest.putAll(previous.latest);
            index.rowKeys.putAll(previous.rowKeys);
            index.locationCards.putAll(previous.locationCards); index.hiddenLocations.putAll(previous.hiddenLocations);
            index.sessions.putAll(previous.sessions);
        }
        String own = state.optString("username");
        // ChatController.publish orders the snapshot by createdAt before dispatch.
        JSONArray messages = state.optJSONArray("messages");
        java.util.Set<String> copiedPeers = new java.util.HashSet<>();
        if (!reuse && messages != null) for (int i = append ? appendFrom : 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            if (!own.equals(item.optString("sender")) && !own.equals(item.optString("recipient"))) continue;
            String peer = own.equals(item.optString("sender"))
                    ? item.optString("recipient") : item.optString("sender");
            if (peer.isEmpty()) continue;
            if (append && copiedPeers.add(peer)) index.copyPeer(peer);
            List<JSONObject> records = index.history.computeIfAbsent(peer, key -> new ArrayList<>());
            int position = records.size();
            records.add(item);
            // Controller snapshots reuse immutable message objects when only another row changed.
            // Reuse their digest too, so a delivery receipt does not rehash the whole history.
            List<JSONObject> prior = previous != null && index.scope.equals(previous.scope)
                    ? previous.history.get(peer) : null;
            String key = prior != null && position < prior.size() && prior.get(position) == item
                    ? previous.rowKey(peer, position) : rowKey(item);
            index.rowKeys.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(key);
            LocationPayload location = LocationPayload.parse(item.optString("body"), System.currentTimeMillis());
            if (location != null) index.addLocation(peer, item.optString("sender"), position, location);
            index.latest.put(peer, item);
        }
        names(state.optJSONArray("conversations"), index.conversations);
        names(state.optJSONArray("contacts"), index.contacts);
        Collections.sort(index.conversations, (a, b) -> time(index.latest.get(b))
                .compareTo(time(index.latest.get(a))));
        String relations = String.valueOf(state.optJSONObject("relationships"));
        String deleted = String.valueOf(state.optJSONObject("deletedPeers"));
        String identityChanges = String.valueOf(state.optJSONObject("identityChanges"));
        StringBuilder rows = new StringBuilder(index.conversations.toString());
        for (String peer : index.conversations) rows.append(index.latest.get(peer));
        index.messageRows = rows.append(relations).append(deleted).append(identityChanges).toString();
        index.contactRows = index.contacts.toString() + relations + deleted + identityChanges;
        return index;
    }

    private void copyPeer(String peer) {
        if (history.containsKey(peer)) history.put(peer, new ArrayList<>(history.get(peer)));
        if (rowKeys.containsKey(peer)) rowKeys.put(peer, new ArrayList<>(rowKeys.get(peer)));
        if (hiddenLocations.containsKey(peer)) hiddenLocations.put(peer, new java.util.HashSet<>(hiddenLocations.get(peer)));
        Map<Integer, LocationCard> cards = locationCards.get(peer);
        if (cards != null) {
            Map<Integer, LocationCard> copied = new HashMap<>();
            Map<LocationCard, LocationCard> clones = new java.util.IdentityHashMap<>();
            for (Map.Entry<Integer, LocationCard> entry : cards.entrySet()) {
                LocationCard source = entry.getValue(), target = new LocationCard();
                target.latest = source.latest; target.coordinate = source.coordinate; target.anchor = source.anchor;
                target.stopped = source.stopped; target.sessionExpires = source.sessionExpires;
                copied.put(entry.getKey(), target); clones.put(source, target);
            }
            locationCards.put(peer, copied);
            Map<String, LocationCard> oldSessions = sessions.get(peer);
            if (oldSessions != null) {
                Map<String, LocationCard> nextSessions = new HashMap<>();
                for (Map.Entry<String, LocationCard> entry : oldSessions.entrySet()) nextSessions.put(entry.getKey(), clones.get(entry.getValue()));
                sessions.put(peer, nextSessions);
            }
        }
    }

    private void addLocation(String peer, String sender, int position, LocationPayload payload) {
        Map<Integer, LocationCard> cards = locationCards.computeIfAbsent(peer, key -> new HashMap<>());
        if (payload.kind.equals("pin")) {
            LocationCard card = new LocationCard(); card.latest = card.coordinate = payload; card.anchor = position;
            cards.put(position, card); return;
        }
        java.util.Set<Integer> hidden = hiddenLocations.computeIfAbsent(peer, key -> new java.util.HashSet<>());
        Map<String, LocationCard> active = sessions.computeIfAbsent(peer, key -> new HashMap<>());
        String session = sender + ":" + payload.sessionId;
        LocationCard card = active.get(session);
        if (card == null) { card = new LocationCard(); card.anchor = position; card.sessionExpires = payload.expiresMillis; active.put(session, card); }
        else if (card.stopped || !payload.kind.equals("stop") && payload.seq <= card.latest.seq) { hidden.add(position); return; }
        else { cards.remove(card.anchor); hidden.add(card.anchor); }
        card.anchor = position;
        card.latest = payload;
        card.sessionExpires = Math.min(card.sessionExpires, payload.expiresMillis);
        card.stopped = payload.kind.equals("stop");
        if (!card.stopped) card.coordinate = payload;
        cards.put(position, card);
    }

    List<JSONObject> messages(String peer) {
        List<JSONObject> result = history.get(peer);
        return result == null ? Collections.emptyList() : result;
    }

    String rowKey(String peer, int position) { return rowKeys.get(peer).get(position); }
    static String rowKey(JSONObject item) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(item.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void names(JSONArray array, List<String> destination) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            String name = array.optString(i);
            if (!name.isEmpty()) unique.add(name);
        }
        destination.addAll(unique);
    }

    private static String time(JSONObject item) {
        return item == null ? "" : item.optString("createdAt");
    }
}

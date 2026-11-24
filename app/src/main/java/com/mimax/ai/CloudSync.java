package com.mimax.ai;

import android.app.Activity;

import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cloud sync scoped to the authenticated Firebase UID. */
public final class CloudSync {
    private CloudSync() {}

    public interface Callback {
        void done(ArrayList<Store.Chat> chats, ArrayList<String> memory, String prompt,
                  String displayName, String publicId, String error);
    }

    private static FirebaseFirestore db() { return FirebaseFirestore.getInstance(); }
    private static String base(FirebaseUser u) { return "user/" + u.getUid(); }

    public static void sync(Activity activity, FirebaseUser user,
                            ArrayList<Store.Chat> localChats, ArrayList<String> localMem,
                            String localPrompt, Callback callback) {
        if (user == null) {
            callback.done(localChats, localMem, localPrompt, null, null, "لا يوجد حساب مسجل");
            return;
        }
        final String path = base(user);
        db().collection(path + "/profile").document("main").get()
            .addOnSuccessListener(profile -> {
                String name = profile.getString("displayName");
                String publicId = profile.getString("publicId");
                if (publicId == null || publicId.isEmpty()) publicId = Store.publicId();
                if (name == null || name.trim().isEmpty()) name = Store.profileName();
                final String finalName = name == null ? "" : name;
                final String finalId = publicId;
                Map<String,Object> profileData = new HashMap<>();
                profileData.put("displayName", finalName);
                profileData.put("publicId", finalId);
                profileData.put("email", user.getEmail() == null ? "" : user.getEmail());
                profileData.put("updatedAt", System.currentTimeMillis());
                db().collection(path + "/profile").document("main").set(profileData);

                db().collection(path + "/deleted").get().addOnSuccessListener(deletedSnap -> {
                    Set<String> deletedIds = new HashSet<>(Store.deletedChatIds());
                    for (DocumentSnapshot d : deletedSnap.getDocuments()) deletedIds.add(d.getId());
                    for (String id : deletedIds) {
                        db().collection(path + "/chats").document(id).delete();
                        db().collection(path + "/deleted").document(id)
                                .set(single("deletedAt", System.currentTimeMillis()));
                    }
                    db().collection(path + "/chats").get().addOnSuccessListener(remote -> {
                        Map<String,Store.Chat> merged = new HashMap<>();
                        for (Store.Chat c : localChats) {
                            if (c != null && c.id != null && !deletedIds.contains(c.id)) merged.put(c.id, c);
                        }
                        for (DocumentSnapshot d : remote.getDocuments()) {
                            if (deletedIds.contains(d.getId())) continue;
                            Store.Chat c = fromDocument(d);
                            if (c == null) continue;
                            Store.Chat old = merged.get(c.id);
                            if (old == null || c.ts > old.ts) merged.put(c.id, c);
                        }
                        ArrayList<Store.Chat> result = new ArrayList<>(merged.values());
                        result.sort((a,b) -> Long.compare(b.ts, a.ts));
                        for (Store.Chat c : result) {
                            db().collection(path + "/chats").document(c.id).set(toMap(c));
                        }

                        // Restore account settings before writing them back. Keep both
                        // local and cloud memory items instead of silently overwriting either side.
                        db().collection(path + "/profile").document("settings").get()
                            .addOnSuccessListener(remoteSettings -> {
                                ArrayList<String> mergedMem = new ArrayList<>();
                                Object rawMem = remoteSettings.get("memory");
                                if (rawMem instanceof List) {
                                    for (Object item : (List<?>) rawMem) {
                                        if (item != null) {
                                            String value = String.valueOf(item);
                                            if (!value.trim().isEmpty() && !mergedMem.contains(value)) mergedMem.add(value);
                                        }
                                    }
                                }
                                if (localMem != null) {
                                    for (String value : localMem) {
                                        if (value != null && !value.trim().isEmpty() && !mergedMem.contains(value)) mergedMem.add(value);
                                    }
                                }
                                String cloudPrompt = remoteSettings.getString("prompt");
                                String mergedPrompt = (localPrompt != null && !localPrompt.trim().isEmpty())
                                        ? localPrompt : (cloudPrompt == null ? "" : cloudPrompt);
                                Map<String,Object> settings = new HashMap<>();
                                settings.put("memory", mergedMem);
                                settings.put("prompt", mergedPrompt);
                                settings.put("updatedAt", System.currentTimeMillis());
                                db().collection(path + "/profile").document("settings").set(settings)
                                    .addOnSuccessListener(v -> callback.done(result, mergedMem, mergedPrompt, finalName, finalId, null))
                                    .addOnFailureListener(e -> callback.done(localChats, localMem, localPrompt, finalName, finalId, message(e)));
                            })
                            .addOnFailureListener(e -> callback.done(localChats, localMem, localPrompt, finalName, finalId, message(e)));
                    }).addOnFailureListener(e -> callback.done(localChats, localMem, localPrompt, finalName, finalId, message(e)));
                }).addOnFailureListener(e -> callback.done(localChats, localMem, localPrompt, finalName, finalId, message(e)));
            }).addOnFailureListener(e -> callback.done(localChats, localMem, localPrompt, null, null, message(e)));
    }

    public static void saveProfile(Activity activity, FirebaseUser user, String name, String publicId) {
        if (user == null) return;
        Map<String,Object> data = new HashMap<>();
        data.put("displayName", name == null ? "" : name.trim());
        data.put("publicId", publicId == null ? Store.publicId() : publicId);
        data.put("email", user.getEmail() == null ? "" : user.getEmail());
        data.put("updatedAt", System.currentTimeMillis());
        db().collection(base(user) + "/profile").document("main").set(data);
    }

    public static void deleteChat(Activity activity, FirebaseUser user, String chatId) {
        if (user == null || chatId == null || chatId.isEmpty()) return;
        String path = base(user);
        db().collection(path + "/deleted").document(chatId)
                .set(single("deletedAt", System.currentTimeMillis()))
                .addOnSuccessListener(v -> db().collection(path + "/chats").document(chatId).delete());
    }

    // ---------------- حذف الحساب بمهلة 30 يوماً ----------------
    public static final long GRACE_MS = 30L * 24 * 60 * 60 * 1000;

    public interface DeletionStatus { void done(Long scheduledAt, String error); }
    public interface Done { void done(String error); }

    /** يقرأ موعد الحذف المجدول (null = لا يوجد طلب حذف). */
    public static void getDeletion(FirebaseUser user, DeletionStatus cb) {
        db().collection(base(user) + "/profile").document("deletion").get()
            .addOnSuccessListener(d -> cb.done(d.exists() ? d.getLong("scheduledAt") : null, null))
            .addOnFailureListener(e -> cb.done(null, message(e)));
    }

    public static void requestDeletion(FirebaseUser user, Done cb) {
        long now = System.currentTimeMillis();
        Map<String,Object> m = new HashMap<>();
        m.put("requestedAt", now);
        m.put("scheduledAt", now + GRACE_MS);
        db().collection(base(user) + "/profile").document("deletion").set(m)
            .addOnSuccessListener(v -> cb.done(null))
            .addOnFailureListener(e -> cb.done(message(e)));
    }

    public static void cancelDeletion(FirebaseUser user, Done cb) {
        db().collection(base(user) + "/profile").document("deletion").delete()
            .addOnSuccessListener(v -> cb.done(null))
            .addOnFailureListener(e -> cb.done(message(e)));
    }

    /** حذف نهائي: كل وثائق المستخدم في Firestore ثم حساب Firebase Auth نفسه. */
    public static void purgeAll(FirebaseUser user, Done cb) {
        final String path = base(user);
        final String[] cols = {"chats", "deleted"};
        final List<Task<?>> tasks = new ArrayList<>();
        final int[] pending = {cols.length};
        final String[] failure = {null};
        for (String col : cols) {
            db().collection(path + "/" + col).get().addOnCompleteListener(t -> {
                if (t.isSuccessful() && t.getResult() != null) {
                    for (DocumentSnapshot d : t.getResult().getDocuments()) tasks.add(d.getReference().delete());
                } else if (failure[0] == null) failure[0] = message(t.getException());
                if (--pending[0] > 0) return;
                for (String id : new String[]{"main", "settings", "deletion"})
                    tasks.add(db().collection(path + "/profile").document(id).delete());
                com.google.android.gms.tasks.Tasks.whenAllComplete(tasks).addOnCompleteListener(all -> {
                    if (failure[0] != null) { cb.done(failure[0]); return; }
                    for (Task<?> x : tasks) if (!x.isSuccessful()) { cb.done(message(x.getException())); return; }
                    user.delete().addOnCompleteListener(del -> cb.done(del.isSuccessful() ? null : message(del.getException())));
                });
            });
        }
    }

    private static Map<String,Object> single(String k, Object v) {
        Map<String,Object> m = new HashMap<>(); m.put(k,v); return m;
    }

    private static Map<String,Object> toMap(Store.Chat c) {
        Map<String,Object> data = new HashMap<>();
        data.put("id", c.id);
        data.put("title", c.title == null ? "محادثة جديدة" : c.title);
        data.put("ts", c.ts);
        ArrayList<Map<String,Object>> msgs = new ArrayList<>();
        for (Store.Msg msg : c.msgs) {
            Map<String,Object> m = new HashMap<>();
            m.put("r", msg.r == null ? "user" : msg.r);
            m.put("t", msg.t == null ? "" : msg.t);
            msgs.add(m);
        }
        data.put("msgs", msgs);
        data.put("updatedAt", System.currentTimeMillis());
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Store.Chat fromDocument(DocumentSnapshot d) {
        try {
            Store.Chat c = new Store.Chat();
            c.id = d.getString("id");
            if (c.id == null || c.id.isEmpty()) c.id = d.getId();
            c.title = d.getString("title");
            if (c.title == null) c.title = "محادثة";
            Long ts = d.getLong("ts");
            c.ts = ts == null ? 0L : ts;
            Object raw = d.get("msgs");
            if (raw instanceof List) {
                for (Object item : (List<?>) raw) {
                    if (!(item instanceof Map)) continue;
                    Map<String,Object> m = (Map<String,Object>) item;
                    Object role = m.get("r"), text = m.get("t");
                    c.msgs.add(new Store.Msg(role == null ? "user" : String.valueOf(role),
                            text == null ? "" : String.valueOf(text)));
                }
            }
            return c;
        } catch (Exception e) { return null; }
    }

    private static String message(Throwable e) {
        return e == null || e.getMessage() == null ? "خطأ اتصال غير معروف" : e.getMessage();
    }
}

package com.example.chatandroid;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Native account and conversation screens driven by ChatController snapshots. */
public final class MainActivity extends Activity implements ChatController.Listener {
    private static final int INK = Color.rgb(29, 39, 42);
    private static final int MUTED = Color.rgb(106, 117, 119);
    private static final int GREEN = Color.rgb(9, 132, 78);
    private static final int BG = Color.rgb(246, 248, 247);
    private static final int BORDER = Color.rgb(228, 233, 230);
    private ChatController controller;
    private ServerUpdates serverUpdates;
    private UpdatePolicy.Release updateRelease;
    private String updateServer = "", updateMessage = "", updatePhase = "";
    private int updatePercent = -1;
    private boolean updateReady;
    private WebRtcCall call;
    private static final int CALL_PERMISSION = 502;
    private String pendingCallMode = "", pendingCallPeer = "";
    private long pendingCallContext;
    private CallSession pendingReadyCall;
    private JSONArray pendingReadyIce;
    private long pendingReadyContext;
    private boolean callToolsExpanded, callPreparing;
    private LinearLayout callActions;
    private LocationSharing locationSharing;
    private String pendingLocationPeer = "", pendingLocationAction = "";
    private static final int LOCATION_PERMISSION = 501;
    private AlertDialog historyDialog;
    private volatile long historySearchGeneration;
    private final Runnable expiryRefresh = new Runnable() {
        @Override public void run() {
            if (!destroyed && conversationStream != null) refreshLocationCards();
            if (!destroyed && accountDeadlineView != null) accountDeadlineView.setText(accountDeadlineText());
            if (!destroyed) main.postDelayed(this, 15000);
        }
    };
    private JSONObject state = new JSONObject();
    private LinearLayout header, tabs;
    private FrameLayout content;
    private EditText composer;
    private ScrollView conversationScroll;
    private int conversationScrollY;
    private boolean conversationAtBottom = true;
    private String page = "messages", detailPeer = "", loginUser = "", draft = "";
    private String messageQuery = "", contactQuery = "";
    private boolean lanTest;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService uiPreparation = Executors.newSingleThreadExecutor();
    private volatile MessageIndex messageIndex = new MessageIndex();
    private volatile long snapshotGeneration;
    private volatile boolean destroyed;
    private boolean foreground;
    private HistoryWindow historyWindow = HistoryWindow.latest(0);
    private int revealedHistoryIndex = -1;
    private LinearLayout conversationStream, messageResults, contactResults;
    private final Map<String, View> messageRows = new HashMap<>(), contactRows = new HashMap<>();
    private TextView conversationStatus;
    private TextView accountDeadlineView;
    private final Map<String, View> messageViews = new HashMap<>();
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String, Long> draftRevisions = new HashMap<>();
    private long nextDraftRevision;
    private String composerDraftKey = "";
    private String renderedScreen = "";
    private LinearLayout shell;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell = column();
        shell.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            // Own all insets: edge-to-edge avoids adjustResize plus IME padding twice.
            getWindow().setDecorFitsSystemWindows(false);
            shell.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                shell.setPadding(Math.max(bars.left, ime.left), bars.top,
                        Math.max(bars.right, ime.right), Math.max(bars.bottom, ime.bottom));
                tabs.setVisibility(ime.bottom > bars.bottom ? View.GONE : View.VISIBLE);
                if (composer != null) composer.setMaxLines(ime.bottom > bars.bottom ? 2 : 5);
                return WindowInsets.CONSUMED;
            });
        } else shell.setFitsSystemWindows(true);
        header = column();
        content = new FrameLayout(this);
        tabs = row();
        tabs.setBackgroundColor(Color.WHITE);
        shell.addView(header);
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        shell.addView(tabs);
        setContentView(shell);
        shell.requestApplyInsets();
        render();
        controller = new ChatController(this, this);
        serverUpdates = new ServerUpdates(this, (generation, address, message, release, percent, ready) -> main.post(() -> {
            if (destroyed || generation != serverUpdates.generation() || !address.equals(server())) return;
            updateServer = address;
            updateMessage = message;
            updateRelease = release;
            updatePercent = percent;
            updateReady = ready;
            updatePhase = percent >= 0 && !ready ? "downloading" : "";
            if (page.equals("settings")) render();
        }));
        call = new WebRtcCall(this, controller);
        locationSharing = new LocationSharing(this, controller, (live, peer, notice) -> main.post(() -> {
            if (!destroyed && notice != null && !notice.isEmpty() && !notice.startsWith("正在分享实时位置")) Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
            if (!destroyed) { header.removeAllViews(); renderHeader(signedIn()); }
        }));
        main.postDelayed(expiryRefresh, 15000);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openNotifiedConversation();
    }

    private void openNotifiedConversation() {
        String peer = getIntent().getStringExtra("messagePeer");
        if (signedIn() && Usernames.valid(peer) && !peer.equals(state.optString("username"))) {
            getIntent().removeExtra("messagePeer");
            openPeer(peer);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (controller != null) controller.setVisibleConversation(true, detailPeer);
        if (signedIn()) CallNotifier.ensurePermission(this);
        openNotifiedConversation();
        if (call != null) call.showPendingIncoming();
        if (pendingReadyCall != null) {
            CallSession ready = pendingReadyCall;
            JSONArray ice = pendingReadyIce;
            long context = pendingReadyContext;
            pendingReadyCall = null;
            pendingReadyIce = null;
            if (context == controller.locationContext() && ready.state != CallSession.State.ENDED
                    && (ready.outgoing || call.session() == ready)) call.start(ready, ice, context);
        }
        main.removeCallbacks(expiryRefresh);
        main.post(expiryRefresh);
    }

    @Override protected void onStop() {
        foreground = false;
        if (controller != null) controller.setVisibleConversation(false, "");
        if (call != null) call.onBackgrounded();
        main.removeCallbacks(expiryRefresh);
        if (locationSharing != null) locationSharing.stopLive();
        pendingLocationPeer = pendingLocationAction = "";
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (call != null) call.finish("", true, "hangup");
        destroyed = true;
        if (serverUpdates != null) serverUpdates.close();
        snapshotGeneration++;
        uiPreparation.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (locationSharing != null) locationSharing.close();
        if (historyDialog != null) historyDialog.dismiss();
        if (controller != null) controller.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        callToolsExpanded = false;
        if (!detailPeer.isEmpty()) {
            saveDraft();
            detailPeer = "";
        } else if (!loginUser.isEmpty()) loginUser = "";
        else if (!page.equals("messages")) page = "messages";
        else { super.onBackPressed(); return; }
        render();
    }

    private boolean signedIn() { return !state.optString("username").isEmpty(); }
    private String server() {
        String selected = state.optString("selectedServer");
        return selected.isEmpty() ? state.optString("server") : selected;
    }

    private void render() {
        if (content == null) return;
        String nextScreen = screenKey();
        Map<String, String> fieldValues = new HashMap<>();
        View focused = getCurrentFocus();
        String focusTag = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : "";
        int fieldCursor = focused instanceof EditText ? ((EditText) focused).getSelectionStart() : 0;
        boolean sameScreen = nextScreen.equals(renderedScreen);
        if (sameScreen) rememberFields(content, fieldValues);
        ScrollView oldPageScroll = findScroll(content);
        int pageScrollY = sameScreen && oldPageScroll != null ? oldPageScroll.getScrollY() : 0;
        boolean restoreFocus = composer != null && composer.hasFocus() && !detailPeer.isEmpty();
        int cursor = composer == null ? 0 : composer.getSelectionStart();
        if (conversationScroll != null && !detailPeer.isEmpty()) {
            conversationScrollY = conversationScroll.getScrollY();
            View child = conversationScroll.getChildAt(0);
            conversationAtBottom = child == null || conversationScrollY +
                    conversationScroll.getHeight() >= child.getHeight() - dp(48);
        }
        saveDraft();
        conversationScroll = null;
        conversationStream = null;
        conversationStatus = null;
        accountDeadlineView = null;
        messageResults = null;
        contactResults = null;
        messageRows.clear();
        contactRows.clear();
        messageViews.clear();
        header.removeAllViews();
        content.removeAllViews();
        tabs.removeAllViews();
        boolean signed = signedIn();
        renderHeader(signed);
        if (signed) {
            if (!detailPeer.isEmpty()) renderConversation();
            else if (page.equals("contacts")) renderContacts();
            else if (page.equals("settings")) renderSettings();
            else renderMessages();
            renderTabs();
        } else if (page.equals("settings")) renderSettings();
        else if (server().isEmpty()) renderFirstUse();
        else if (!loginUser.isEmpty()) renderAuth();
        else renderAccounts();
        renderedScreen = nextScreen;
        if (sameScreen) {
            restoreFields(content, fieldValues, focusTag, fieldCursor);
            ScrollView newScroll = findScroll(content);
            if (newScroll != null && detailPeer.isEmpty()) newScroll.post(() -> newScroll.scrollTo(0, pageScrollY));
        }
        if (restoreFocus && composer != null) {
            composer.requestFocus();
            composer.setSelection(Math.min(Math.max(cursor, 0), composer.length()));
        }
        shell.requestApplyInsets();
        if (controller != null) controller.setVisibleConversation(foreground, detailPeer);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        render();
    }

    private String screenKey() {
        return server() + ":" + state.optString("username") + ":" + page + ":" + detailPeer + ":" + loginUser;
    }

    private void rememberFields(View view, Map<String, String> values) {
        if (view instanceof EditText && view.getTag() instanceof String)
            values.put((String) view.getTag(), ((EditText) view).getText().toString());
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) rememberFields(group.getChildAt(i), values);
        }
    }

    private void restoreFields(View view, Map<String, String> values, String focusTag, int cursor) {
        if (view instanceof EditText && view.getTag() instanceof String) {
            EditText field = (EditText) view;
            String tag = (String) view.getTag();
            if (values.containsKey(tag) && !field.getText().toString().equals(values.get(tag)))
                field.setText(values.get(tag));
            if (tag.equals(focusTag)) {
                field.requestFocus();
                field.setSelection(Math.min(Math.max(0, cursor), field.length()));
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) restoreFields(group.getChildAt(i), values, focusTag, cursor);
        }
    }

    private ScrollView findScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView result = findScroll(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private void renderHeader(boolean signed) {
        LinearLayout bar = row();
        bar.setPadding(dp(16), dp(11), dp(12), dp(11));
        bar.setBackgroundColor(Color.WHITE);
        if (!detailPeer.isEmpty() || !loginUser.isEmpty() && !signed || page.equals("settings") && !signed)
            bar.addView(button("‹", 30, INK, v -> onBackPressed()), new LinearLayout.LayoutParams(dp(40), dp(44)));
        LinearLayout labels = column();
        String title = !detailPeer.isEmpty() ? detailPeer : page.equals("settings") ? "设置" :
                signed ? page.equals("contacts") ? "联系人" : "消息" :
                !loginUser.isEmpty() ? loginUser.equals("__new__") ? "注册新账号" :
                        loginUser.equals("__login__") ? "登录已有账号" : "登录 " + loginUser : "Chat";
        labels.addView(text(title, 23, true, INK));
        String subtitle = !detailPeer.isEmpty() ? peerStatus(detailPeer) :
                signed && !page.equals("settings") ?
                        (state.optBoolean("online") ? "在线" : "离线") :
                        server().replaceFirst("^https?://", "");
        if (!subtitle.isEmpty()) labels.addView(text(subtitle, 12, false, MUTED));
        bar.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (signed && !detailPeer.isEmpty()) {
            TextView more = button("⋮", 27, INK, v -> showChatMenu());
            more.setContentDescription("聊天菜单");
            bar.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
            JSONObject sharing = state.optJSONObject("locationSharing");
            if (sharing != null && sharing.optBoolean("active"))
                bar.addView(button("停止共享", 13, GREEN, v -> locationSharing.stopLive()));
        }
        if (signed && detailPeer.isEmpty() && !page.equals("settings"))
            bar.addView(button("＋", 27, INK, v -> promptPeer()), new LinearLayout.LayoutParams(dp(44), dp(44)));
        if (!signed && page.equals("messages") && !server().isEmpty() && loginUser.isEmpty())
            bar.addView(button("设置", 14, GREEN, v -> { page = "settings"; render(); }));
        header.addView(bar);
        String error = state.optString("uiError");
        if (!error.isEmpty()) {
            TextView notice = text(error, 13, false, Color.rgb(168, 43, 40));
            notice.setPadding(dp(18), dp(8), dp(18), dp(8));
            notice.setBackgroundColor(Color.rgb(255, 239, 235));
            header.addView(notice);
        }
        if (signed && !state.optBoolean("online") && !state.optString("status").equals("连接中")) {
            TextView reconnect = button("连接失败？检查网络或服务器地址；网络恢复后会自动重连", 12, GREEN,
                    v -> { page = "settings"; detailPeer = ""; render(); });
            reconnect.setBackground(rounded(Color.rgb(237, 246, 241), 10, BORDER));
            header.addView(reconnect);
        }
        if (signed && !state.optBoolean("online")) {
            long expiry = accountDeadlineMillis();
            if (expiry > 0 && expiry - System.currentTimeMillis() <= 2L * 86400000L) {
                TextView deadline = hint(accountDeadlineText());
                deadline.setBackground(rounded(Color.rgb(255, 248, 230), 10, BORDER));
                header.addView(deadline);
            }
        }
    }

    private void renderFirstUse() {
        LinearLayout body = padded();
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(text("欢迎使用 Chat", 27, true, INK));
        TextView intro = text("先添加一个聊天服务器。之后可以在设置中保存并切换多个服务器。", 16, false, MUTED);
        intro.setPadding(0, dp(12), 0, dp(25));
        body.addView(intro);
        body.addView(primary("设置服务器地址", v -> promptServer()), new LinearLayout.LayoutParams(-1, dp(50)));
        content.addView(body);
    }

    private void renderAccounts() {
        ScrollView scroll = scroll();
        LinearLayout body = padded();
        scroll.addView(body);
        body.addView(section("选择账号"));
        JSONArray accounts = accounts();
        if (accounts.length() == 0) body.addView(hint("此服务器上还没有保存在本设备的账号。"));
        for (int i = 0; i < accounts.length(); i++) {
            String user = accounts.optString(i);
            if (!user.isEmpty()) {
                AccountStatusCache.Deadline cached = AccountStatusCache.load(this, server(), user);
                String note = cached == null ? "输入密码登录" : deadlineText(cached.localExpiryMillis, false);
                body.addView(rowItem(user, note, () -> { loginUser = user; render(); }));
            }
        }
        body.addView(space(16));
        body.addView(primary("注册新账号", v -> { loginUser = "__new__"; render(); }), new LinearLayout.LayoutParams(-1, dp(50)));
        body.addView(space(12));
        body.addView(secondary("登录其他账号", v -> { loginUser = "__login__"; render(); }), new LinearLayout.LayoutParams(-1, dp(48)));
        body.addView(space(12));
        body.addView(secondary("切换服务器", v -> { page = "settings"; render(); }), new LinearLayout.LayoutParams(-1, dp(48)));
        content.addView(scroll);
    }

    private void renderAuth() {
        boolean register = loginUser.equals("__new__");
        boolean manualLogin = loginUser.equals("__login__");
        ScrollView scroll = scroll();
        LinearLayout body = padded();
        scroll.addView(body);
        body.addView(section(register ? "创建账号" : "欢迎回来"));
        body.addView(hint("服务器"));
        body.addView(readonly(server()));
        body.addView(space(13));
        EditText username = null;
        if (register || manualLogin) {
            body.addView(hint("用户名"));
            username = field("2–32 位汉字、小写字母、数字、下划线", InputType.TYPE_CLASS_TEXT);
            username.setSingleLine(true);
            body.addView(username);
        } else {
            body.addView(hint("账号"));
            body.addView(readonly(loginUser));
        }
        body.addView(space(13));
        body.addView(hint("密码"));
        EditText password = field("至少 8 字符", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        body.addView(password);
        body.addView(space(14));
        if (BuildConfig.DEBUG && server().startsWith("http://") && !isLoopback(server())) {
            CheckBox box = new CheckBox(this);
            box.setText("允许局域网 HTTP 测试（明文传输）");
            box.setChecked(lanTest);
            box.setOnCheckedChangeListener((b, checked) -> lanTest = checked);
            body.addView(box);
        }
        if (register) {
            LinearLayout notice = column();
            notice.setPadding(dp(15), dp(14), dp(15), dp(14));
            notice.setBackground(rounded(Color.rgb(255, 248, 230), 11, Color.rgb(224, 189, 113)));
            notice.addView(text("注册前请务必阅读", 16, true, Color.rgb(130, 83, 20)));
            String[] rules = {
                    "1. 用户名和密码目前不支持修改，请妥善保管。",
                    "2. 清除应用数据或卸载后，本机密钥和聊天历史会丢失。账号未被清理时，可凭原用户名和密码重新登录，重建加密身份；旧设备会退出，联系人需重新核对安全码。旧历史无法恢复。",
                    "3. 连续 7 天未成功认证连接服务器，服务器会清理账号和服务器数据。请每周登录，并确认显示在线。",
                    "4. 首次联系只能发送一条消息；对方接受后，才能继续发送。"
            };
            for (String rule : rules) {
                TextView item = text(rule, 14, false, INK);
                item.setPadding(0, dp(10), 0, 0);
                item.setLineSpacing(dp(3), 1f);
                notice.addView(item);
            }
            body.addView(notice, new LinearLayout.LayoutParams(-1, -2));
            body.addView(space(16));
        }
        EditText nameField = username;
        body.addView(primary(register ? "注册并登录" : "登录", v -> {
            String user = register || manualLogin ? nameField.getText().toString().trim() : loginUser;
            if (!Usernames.valid(user)) { showError("用户名限 2–32 位汉字、小写字母、数字、下划线（纯英文至少 3 位）"); return; }
            String secret = password.getText().toString();
            if (secret.length() < 8) { showError("密码至少 8 字符"); return; }
            password.setText("");
            hideKeyboard(password);
            showError("正在" + (register ? "注册" : "登录") + "…");
            controller.login(server(), user, secret, register, lanTest);
        }), new LinearLayout.LayoutParams(-1, dp(50)));
        TextView security = hint("重新登录已有账号可重建加密身份；旧设备会退出，联系人需重新核对安全码。本机丢失的历史无法找回。");
        security.setPadding(0, dp(18), 0, 0);
        body.addView(security);
        content.addView(scroll);
    }

    private void renderMessages() {
        ScrollView scroll = scroll();
        LinearLayout body = column();
        scroll.addView(body);
        LinearLayout results = column();
        messageResults = results;
        addSearch(body, "搜索会话", messageQuery, query -> {
            messageQuery = query;
            populateMessages(results);
        });
        body.addView(results);
        populateMessages(results);
        content.addView(scroll);
    }

    private void populateMessages(LinearLayout results) {
        List<String> peers = messageIndex.conversations;
        Map<String, JSONObject> last = messageIndex.latest;
        String query = messageQuery.trim().toLowerCase(java.util.Locale.ROOT);
        List<View> desired = new ArrayList<>();
        Map<String, View> retained = new HashMap<>();
        for (String peer : peers) {
            if (!matchesNormalized(peer, query)) continue;
            JSONObject message = last.get(peer);
            String preview = message == null ? "点击开始聊天" : HistorySearch.preview(message);
            int unread = state.optJSONObject("unread") == null ? 0 : state.optJSONObject("unread").optInt(peer);
            String subtitle = (unread > 0 ? "未读 " + unread + " 条 · " : "") + peerStatus(peer) + " · " + preview;
            String key = peer + '\0' + subtitle;
            View line = messageRows.get(key);
            if (line == null) line = listActionRow(peer, subtitle, "清除",
                    () -> openPeer(peer), v -> confirmClearConversation(peer));
            retained.put(key, line);
            desired.add(line);
        }
        messageRows.clear();
        messageRows.putAll(retained);
        if (desired.isEmpty()) desired.add(empty(messageQuery.isEmpty() ? "还没有会话" : "没有匹配的会话",
                messageQuery.isEmpty() ? "点击右上角＋，输入用户名开始聊天。" : "试试其他用户名。"));
        reconcileRows(results, desired);
    }

    private void renderContacts() {
        ScrollView scroll = scroll();
        LinearLayout body = column();
        scroll.addView(body);
        LinearLayout results = column();
        contactResults = results;
        addSearch(body, "搜索联系人", contactQuery, query -> {
            contactQuery = query;
            populateContacts(results);
        });
        body.addView(results);
        populateContacts(results);
        content.addView(scroll);
    }

    private void populateContacts(LinearLayout results) {
        List<String> contacts = messageIndex.contacts;
        String query = contactQuery.trim().toLowerCase(java.util.Locale.ROOT);
        List<View> desired = new ArrayList<>();
        Map<String, View> retained = new HashMap<>();
        for (String peer : contacts) {
            if (!matchesNormalized(peer, query)) continue;
            String subtitle = peerStatus(peer);
            String key = "contact\0" + peer + '\0' + subtitle;
            View line = contactRows.get(key);
            if (line == null) line = listActionRow(peer, subtitle, "删除",
                    () -> openPeer(peer), v -> confirmRemoveContact(peer));
            retained.put(key, line);
            desired.add(line);
        }
        JSONObject relations = state.optJSONObject("relationships");
        if (relations != null) for (java.util.Iterator<String> names = relations.keys(); names.hasNext();) {
            String peer = names.next();
            if (!"pending_incoming".equals(relation(peer)) || !matchesNormalized(peer, query)) continue;
            String subtitle = "请求与你聊天 · " + peerStatus(peer);
            String key = "request\0" + peer + '\0' + subtitle;
            View line = contactRows.get(key);
            if (line == null) line = listActionRow(peer, subtitle, "同意",
                    () -> openPeer(peer), v -> controller.acceptContact(peer));
            retained.put(key, line);
            desired.add(line);
        }
        contactRows.clear();
        contactRows.putAll(retained);
        if (desired.isEmpty()) desired.add(empty(contactQuery.isEmpty() ? "暂无联系人" : "没有匹配的联系人",
                contactQuery.isEmpty() ? "同意聊天请求后，对方会出现在这里。" : "试试其他用户名。"));
        reconcileRows(results, desired);
    }

    private View listActionRow(String peer, String subtitle, String action,
                               Runnable open, View.OnClickListener onAction) {
        LinearLayout line = row();
        line.setBackground(interactiveBackground(Color.WHITE, 15, BORDER));
        line.addView(rowItem(peer, subtitle, open), new LinearLayout.LayoutParams(0, -2, 1));
        TextView actionButton = button(action, 13, action.equals("同意") ? GREEN : MUTED, onAction);
        actionButton.setContentDescription(action + peer);
        line.addView(actionButton);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(14), dp(4), dp(14), dp(4));
        line.setLayoutParams(params);
        return line;
    }

    private void reconcileRows(LinearLayout parent, List<View> desired) {
        java.util.HashSet<View> wanted = new java.util.HashSet<>(desired);
        for (int i = parent.getChildCount() - 1; i >= 0; i--)
            if (!wanted.contains(parent.getChildAt(i))) parent.removeViewAt(i);
        for (int i = 0; i < desired.size(); i++) {
            View child = desired.get(i);
            if (i < parent.getChildCount() && parent.getChildAt(i) == child) continue;
            if (child.getParent() == parent) parent.removeView(child);
            parent.addView(child, i);
        }
    }

    private void renderConversation() {
        LinearLayout body = column();
        String relation = relation(detailPeer);
        boolean deleted = isDeleted(detailPeer);
        if (deleted) body.addView(hint("该用户已销户，无法向不存在的账号发送消息。历史记录仍保留。"));
        if (identityChanged(detailPeer)) {
            TextView notice = hint("对方的加密身份已更新。请打开安全码，通过其他可信渠道重新核对并确认后再发送消息。");
            notice.setPadding(dp(16), dp(10), dp(16), dp(10));
            notice.setBackgroundColor(Color.rgb(255, 248, 230));
            body.addView(notice);
            body.addView(button("打开安全码并核对  ›", 14, GREEN,
                    v -> controller.safety(detailPeer, false, null)));
        }
        if (relation.equals("pending_incoming")) {
            LinearLayout request = row();
            request.setPadding(dp(16), dp(10), dp(16), dp(10));
            request.setBackgroundColor(Color.rgb(232, 247, 237));
            request.addView(text("对方请求与你聊天", 14, false, INK),
                    new LinearLayout.LayoutParams(0, -2, 1));
            request.addView(button("同意", 15, GREEN, v -> controller.acceptContact(detailPeer)));
            body.addView(request);
        } else if (relation.equals("pending_outgoing")) {
            TextView pending = hint("首条消息已发出，等待对方在“联系人”页接受；接受后即可继续聊天。");
            pending.setPadding(dp(16), dp(8), dp(16), dp(8));
            body.addView(pending);
        }
        ScrollView scroll = scroll();
        conversationScroll = scroll;
        LinearLayout stream = column();
        stream.setPadding(dp(14), dp(10), dp(14), dp(14));
        scroll.addView(stream);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        conversationStream = stream;
        updateConversationStream(false);
        LinearLayout tools = row();
        tools.setPadding(dp(16), dp(5), dp(16), dp(5));
        conversationStatus = text(state.optString("status"), 12, false, MUTED);
        tools.addView(conversationStatus, new LinearLayout.LayoutParams(0, -2, 1));
        if (!deleted) tools.addView(button("安全码  ›", 13, GREEN, v -> controller.safety(detailPeer, false, null)));
        body.addView(tools);
        LinearLayout bar = row();
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(BG);
        composer = field("发送消息", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composerDraftKey = draftKey(detailPeer);
        composer.setText(draft);
        String editingKey = composerDraftKey;
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                draftRevisions.put(editingKey, ++nextDraftRevision);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        composer.setMaxLines(5);
        composer.setMinHeight(dp(48));
        boolean canSend = !deleted && !identityChanged(detailPeer) && !relation.startsWith("pending_");
        composer.setEnabled(canSend);
        if (!canSend) composer.setHint(deleted ? "该用户已销户，不能发送" : identityChanged(detailPeer) ? "重新核对安全码后可发送" : relation.equals("pending_incoming") ? "同意后可以回复" : "等待对方同意");
        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(0, -2, 1);
        composerParams.setMargins(0, 0, dp(8), 0);
        bar.addView(composer, composerParams);
        TextView sendButton = button("发送", 16, canSend ? GREEN : MUTED, v -> {
            String message = composer.getText().toString();
            if (message.trim().isEmpty()) return;
            draft = message;
            String key = composerDraftKey;
            controller.send(detailPeer, message, key, draftRevisions.getOrDefault(key, 0L));
        });
        sendButton.setEnabled(canSend);
        sendButton.setBackground(interactiveBackground(canSend ? GREEN : BORDER, 12, canSend ? GREEN : BORDER));
        sendButton.setTextColor(canSend ? Color.WHITE : MUTED);
        bar.addView(sendButton, new LinearLayout.LayoutParams(dp(64), dp(48)));
        TextView callToggle = button("＋", 27, GREEN, v -> {
            callToolsExpanded = !callToolsExpanded;
            if (callActions != null) callActions.setVisibility(callToolsExpanded ? View.VISIBLE : View.GONE);
        });
        callToggle.setContentDescription("展开聊天功能");
        bar.addView(callToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        body.addView(bar);
        callActions = row();
        callActions.setBackgroundColor(BG);
        callActions.setPadding(dp(12), dp(4), dp(12), dp(12));
        callActions.addView(button("语音通话", 13, GREEN, v -> requestCallPermissions("audio")),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        callActions.addView(button("视频通话", 13, GREEN, v -> requestCallPermissions("video")),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        callActions.addView(button("发送位置", 13, GREEN, v -> requestLocation(detailPeer, "pin")),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        callActions.addView(button("共享位置", 13, GREEN, v -> promptLiveLocation()),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        callActions.setVisibility(callToolsExpanded ? View.VISIBLE : View.GONE);
        body.addView(callActions);
        content.addView(body);
        boolean atBottom = conversationAtBottom;
        int previousY = conversationScrollY;
        scroll.post(() -> {
            if (scroll != conversationScroll) return;
            if (atBottom) scroll.fullScroll(View.FOCUS_DOWN);
            else scroll.scrollTo(0, previousY);
        });
    }

    private String visibleHistoryKey() {
        List<JSONObject> history = messageIndex.messages(detailPeer);
        StringBuilder key = new StringBuilder().append(history.size()).append(':')
                .append(historyWindow.start).append(':').append(historyWindow.end).append(':');
        for (int i = historyWindow.start; i < Math.min(historyWindow.end, history.size()); i++)
            key.append(messageViewKey(i)).append(':');
        return key.toString();
    }

    private void updateConversationStream(boolean preservePosition) {
        if (conversationStream == null || conversationScroll == null) return;
        LinearLayout stream = conversationStream;
        ScrollView scroll = conversationScroll;
        int oldY = scroll.getScrollY();
        boolean atBottom = oldY + scroll.getHeight() >= stream.getHeight() - dp(48);
        View anchor = null;
        int anchorTop = 0;
        if (preservePosition && !atBottom) for (int i = 0; i < stream.getChildCount(); i++) {
            View child = stream.getChildAt(i);
            if (child.getBottom() > oldY) { anchor = child; anchorTop = child.getTop(); break; }
        }
        List<JSONObject> history = messageIndex.messages(detailPeer);
        int start = historyWindow.start, end = Math.min(historyWindow.end, history.size());
        List<View> desired = new ArrayList<>();
        Map<String, View> retained = new HashMap<>();
        if (start > 0) {
            String key = "older:" + start;
            View older = messageViews.get(key);
            if (older == null) older = button("加载更早的消息（还有 " + start + " 条）", 14, GREEN, v -> {
                // Loading is explicit; no record is removed from local storage.
                historyWindow = historyWindow.older();
                updateConversationStream(true);
            });
            retained.put(key, older);
            desired.add(older);
        }
        for (int i = start; i < end; i++) {
            JSONObject item = history.get(i);
            if (messageIndex.hidden(detailPeer, i) && i != revealedHistoryIndex) continue;
            String key = messageViewKey(i);
            View line = messageViews.get(key);
            if (line == null) {
                MessageIndex.LocationCard card = messageIndex.card(detailPeer, i);
                if (card == null && messageIndex.hidden(detailPeer, i) && i == revealedHistoryIndex) {
                    LocationPayload payload = LocationPayload.parse(item.optString("body"), System.currentTimeMillis());
                    if (payload != null) {
                        card = new MessageIndex.LocationCard(); card.latest = payload;
                        card.coordinate = payload.kind.equals("stop") ? null : payload;
                        card.stopped = payload.kind.equals("stop"); card.sessionExpires = payload.expiresMillis;
                    }
                }
                line = messageBubble(item, card);
            }
            retained.put(key, line);
            desired.add(line);
        }
        if (historyWindow.hasNewer(history.size())) {
            String key = "newer:" + end + ":" + history.size();
            View newer = messageViews.get(key);
            if (newer == null) newer = button("加载较新的消息（还有 " + (history.size() - end) + " 条）", 14, GREEN, v -> {
                historyWindow = historyWindow.newer(messageIndex.messages(detailPeer).size());
                updateConversationStream(true);
            });
            retained.put(key, newer);
            desired.add(newer);
            String latestKey = "latest:" + history.size();
            View latest = messageViews.get(latestKey);
            if (latest == null) latest = button("返回最新消息", 14, GREEN, v -> {
                historyWindow = HistoryWindow.latest(messageIndex.messages(detailPeer).size());
                revealedHistoryIndex = -1;
                updateConversationStream(false);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
            retained.put(latestKey, latest);
            desired.add(latest);
        }
        if (history.isEmpty()) {
            View empty = messageViews.get("empty");
            if (empty == null) empty = empty("暂无消息", "发送第一条端到端加密消息。");
            retained.put("empty", empty);
            desired.add(empty);
        }
        java.util.HashSet<View> wanted = new java.util.HashSet<>(desired);
        for (int i = stream.getChildCount() - 1; i >= 0; i--)
            if (!wanted.contains(stream.getChildAt(i))) stream.removeViewAt(i);
        for (int i = 0; i < desired.size(); i++) {
            View child = desired.get(i);
            if (i < stream.getChildCount() && stream.getChildAt(i) == child) continue;
            if (child.getParent() == stream) stream.removeView(child);
            stream.addView(child, i);
        }
        messageViews.clear();
        messageViews.putAll(retained);
        if (preservePosition) {
            final View savedAnchor = anchor;
            final int savedTop = anchorTop;
            scroll.post(() -> {
                if (scroll != conversationScroll) return;
                if (savedAnchor != null && savedAnchor.getParent() == stream)
                    scroll.scrollTo(0, oldY + savedAnchor.getTop() - savedTop);
                else if (atBottom) scroll.fullScroll(View.FOCUS_DOWN);
                else scroll.scrollTo(0, oldY);
            });
        }
    }

    private String messageViewKey(int index) {
        MessageIndex.LocationCard card = messageIndex.card(detailPeer, index);
        JSONObject item = messageIndex.messages(detailPeer).get(index);
        JSONObject pending = state.optJSONObject("pendingClientIds");
        String delivery = pending != null && pending.optBoolean(item.optString("clientId"))
                ? ":pending:" + state.optBoolean("online") + ':'
                        + (state.optJSONObject("rejectedClientIds") != null
                        && state.optJSONObject("rejectedClientIds").has(item.optString("clientId"))) + ':'
                        + (state.optJSONObject("timedOutClientIds") != null
                        && state.optJSONObject("timedOutClientIds").has(item.optString("clientId"))) : "";
        return index + ":" + messageIndex.rowKey(detailPeer, index) + delivery
                + (card == null ? "" : ":" + card.key());
    }

    private View messageBubble(JSONObject item, MessageIndex.LocationCard location) {
        boolean outgoing = state.optString("username").equals(item.optString("sender"));
        LinearLayout bubble = column();
        bubble.setPadding(dp(13), dp(9), dp(13), dp(9));
        bubble.setBackground(rounded(outgoing ? Color.rgb(214, 246, 220) : Color.WHITE, 14, BORDER));
        if (location == null) bubble.addView(text(item.optString("body"), 16, false, INK));
        else addLocationCard(bubble, location);
        TextView sentTime = text(MessageTime.format(item.optString("createdAt", "")), 11, false, MUTED);
        sentTime.setPadding(0, dp(5), 0, 0);
        bubble.addView(sentTime);
        String status = item.optString("status");
        String clientId = item.optString("clientId");
        JSONObject pendingIds = state.optJSONObject("pendingClientIds");
        boolean pending = outgoing && pendingIds != null && pendingIds.optBoolean(clientId);
        JSONObject rejectedIds = state.optJSONObject("rejectedClientIds");
        boolean rejected = pending && rejectedIds != null && rejectedIds.has(clientId);
        JSONObject timedOutIds = state.optJSONObject("timedOutClientIds");
        boolean timedOut = pending && timedOutIds != null && timedOutIds.has(clientId);
        if (rejected) status = "发送失败 · 服务器拒绝（点按重试）";
        else if (timedOut && state.optBoolean("online")) status = "发送失败 · 未获确认（点按重试）";
        else if (pending) status = state.optBoolean("online") ? "发送中 · 未确认（点按重试）"
                : "等待连接恢复 · 将自动重试";
        else if (outgoing && status.equals("服务器已保存密文")) status = "已送达服务器";
        else if (outgoing && status.equals("对方客户端已接收")) status = "已送达对方设备";
        if (outgoing && !status.isEmpty()) {
            TextView delivery = text(status, 11, false, MUTED);
            delivery.setPadding(0, dp(5), 0, 0);
            if (pending) {
                delivery.setTextColor(GREEN);
                delivery.setOnClickListener(v -> controller.retryPending(clientId));
            }
            bubble.addView(delivery);
        }
        LinearLayout line = row();
        line.setGravity(outgoing ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, dp(5), 0, dp(5));
        line.addView(bubble, params);
        return line;
    }

    private void refreshLocationCards() {
        if (conversationStream == null) return;
        refreshLocationLabels(conversationStream);
    }

    private void refreshLocationLabels(View view) {
        Object tag = view.getTag();
        if (view instanceof TextView && tag instanceof MessageIndex.LocationCard)
            ((TextView) view).setText(locationStatus((MessageIndex.LocationCard) tag));
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) refreshLocationLabels(group.getChildAt(i));
        }
    }

    private String locationStatus(MessageIndex.LocationCard card) {
        if (card.latest.kind.equals("pin")) return "当前位置";
        if (card.stopped) return "实时位置 · 已停止";
        if (System.currentTimeMillis() >= card.sessionExpires) return "实时位置 · 已过期";
        return "实时位置 · 共享中，截止 " + MessageTime.format(java.time.Instant.ofEpochMilli(card.sessionExpires).toString());
    }

    private void addLocationCard(LinearLayout bubble, MessageIndex.LocationCard card) {
        TextView status = text(locationStatus(card), 16, true, INK);
        status.setTag(card); bubble.addView(status);
        LocationPayload point = card.coordinate;
        if (point != null) {
            // Offline drawn preview: no external tiles, geocoding or automatic coordinate requests.
            LocationPreview preview = new LocationPreview(this, point.latitude, point.longitude);
            bubble.addView(preview, new LinearLayout.LayoutParams(dp(235), dp(116)));
            bubble.addView(text(String.format(java.util.Locale.ROOT, "纬度 %.6f · 经度 %.6f", point.latitude, point.longitude), 12, false, INK));
            bubble.addView(text((point.accuracy > 0 ? "精度约 " + Math.round(point.accuracy) + " 米" : "精度未提供") + " · " + MessageTime.format(point.recordedAt), 12, false, MUTED));
            TextView open = button("用地图应用打开", 13, GREEN, v -> openLocationInMap(point));
            bubble.addView(open);
        } else bubble.addView(text("位置共享已结束", 13, false, MUTED));
    }

    private void openLocationInMap(LocationPayload point) {
        new AlertDialog.Builder(this).setTitle("选择地图 · 坐标将提供给外部地图")
                .setItems(new String[]{"高德地图", "选择其他地图应用", "浏览器打开 OpenStreetMap"}, (dialog, selected) -> {
                    try {
                        if (selected == 0) {
                            Intent amap = new Intent(Intent.ACTION_VIEW, Uri.parse(point.amapUri()));
                            amap.setPackage("com.autonavi.minimap");
                            startActivity(amap);
                        } else if (selected == 1) {
                            Intent map = new Intent(Intent.ACTION_VIEW, Uri.parse(point.geoUri()));
                            startActivity(Intent.createChooser(map, "选择地图应用"));
                        } else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(point.mapsUrl())));
                    } catch (Exception unavailable) {
                        Toast.makeText(this, selected == 0 ? "未安装高德地图，请选择其他地图应用" : "没有可用的地图应用", Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showChatMenu() {
        new AlertDialog.Builder(this).setTitle("聊天菜单")
                .setItems(new String[]{"查找聊天记录"}, (d, which) -> showHistorySearch()).show();
    }

    private boolean canSendLocation(String peer) {
        return signedIn() && state.optBoolean("online") && !isDeleted(peer)
                && !identityChanged(peer) && "accepted".equals(relation(peer));
    }

    private void promptLiveLocation() {
        if (locationSharing.isLive()) {
            new AlertDialog.Builder(this).setTitle("实时位置共享中")
                    .setMessage("正在与 " + locationSharing.peer() + " 共享位置。")
                    .setNegativeButton("继续共享", null)
                    .setPositiveButton("停止共享", (dialog, which) -> locationSharing.stopLive()).show();
            return;
        }
        if (!canSendLocation(detailPeer)) {
            Toast.makeText(this, "位置需要在线且双方已同意聊天", Toast.LENGTH_LONG).show();
            return;
        }
        String peer = detailPeer;
        new AlertDialog.Builder(this).setTitle("共享实时位置")
                .setMessage("仅在应用前台、在线时发送位置，最多 1 小时。离开应用或断线会停止，可随时点“停止共享”。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始共享", (dialog, which) -> requestLocation(peer, "live")).show();
    }

    private void requestLocation(String peer, String action) {
        if (!canSendLocation(peer)) {
            Toast.makeText(this, "位置需要在线且双方已同意聊天", Toast.LENGTH_LONG).show();
            return;
        }
        if (locationSharing.hasPermission()) { performLocation(peer, action); return; }
        pendingLocationPeer = peer;
        pendingLocationAction = action;
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == CALL_PERMISSION) {
            if (callPermissionsGranted(pendingCallMode)) beginPermittedCall();
            else {
                if (call != null && call.busy()) call.finish("需要麦克风和摄像头权限", true, "reject");
                else Toast.makeText(this, "通话需要麦克风和摄像头权限", Toast.LENGTH_LONG).show();
                pendingCallMode = pendingCallPeer = "";
            }
            return;
        }
        if (code != LOCATION_PERMISSION) return;
        String peer = pendingLocationPeer, action = pendingLocationAction;
        pendingLocationPeer = pendingLocationAction = "";
        if (peer.isEmpty()) return;
        if (locationSharing.hasPermission() && canSendLocation(peer)) performLocation(peer, action);
        else Toast.makeText(this, "未授予位置权限，无法发送位置；聊天和历史查找仍可使用", Toast.LENGTH_LONG).show();
    }

    private void performLocation(String peer, String action) {
        if ("live".equals(action)) locationSharing.startLive(peer);
        else locationSharing.requestPin(peer);
    }

    private void showHistorySearch() {
        final String peer = detailPeer, scope = draftKey(peer);
        LinearLayout fields = column();
        fields.setPadding(dp(16), dp(8), dp(16), dp(8));
        fields.addView(hint("仅查找本设备已解密缓存；日期按本机时区，包含首尾日期"));
        EditText query = field("搜索消息正文", InputType.TYPE_CLASS_TEXT);
        EditText from = field("开始日期 YYYY-MM-DD（可选）", InputType.TYPE_CLASS_TEXT);
        EditText to = field("结束日期 YYYY-MM-DD（可选）", InputType.TYPE_CLASS_TEXT);
        query.setSingleLine(true); from.setSingleLine(true); to.setSingleLine(true);
        fields.addView(query); fields.addView(from); fields.addView(to);
        TextView status = hint("输入关键词或日期，也可浏览全部本地记录");
        fields.addView(status);
        ScrollView resultScroll = scroll();
        LinearLayout results = column();
        resultScroll.addView(results);
        fields.addView(resultScroll, new LinearLayout.LayoutParams(-1, dp(280)));
        final List<JSONObject> records = new ArrayList<>(messageIndex.messages(peer));
        final List<Integer> found = new ArrayList<>();
        final int[] shown = {0};
        Runnable[] display = new Runnable[1];
        display[0] = () -> {
            results.removeAllViews();
            int limit = Math.min(found.size(), shown[0]);
            for (int n = 0; n < limit; n++) {
                int index = found.get(n);
                JSONObject item = records.get(index);
                results.addView(rowItem(item.optString("sender"),
                        MessageTime.format(item.optString("createdAt")) + " · " + historyPreview(item), () -> {
                            if (!scope.equals(draftKey(detailPeer))) return;
                            if (historyDialog != null) historyDialog.dismiss();
                            jumpToMessage(item);
                        }));
            }
            if (limit < found.size()) results.addView(button("加载更多结果", 14, GREEN, v -> {
                shown[0] += HistorySearch.PAGE_SIZE; display[0].run();
            }));
        };
        Runnable[] pending = new Runnable[1];
        Runnable search = () -> {
            if (pending[0] != null) main.removeCallbacks(pending[0]);
            long token = ++historySearchGeneration;
            String q = query.getText().toString(), start = from.getText().toString(), end = to.getText().toString();
            pending[0] = () -> {
                try { uiPreparation.execute(() -> {
                    if (destroyed || token != historySearchGeneration) return;
                    List<Integer> matches;
                    String failure = "";
                    try { matches = HistorySearch.find(records, q, start, end, java.time.ZoneId.systemDefault(),
                            () -> destroyed || token != historySearchGeneration); }
                    catch (IllegalArgumentException invalid) { matches = new ArrayList<>(); failure = invalid.getMessage(); }
                    if (destroyed || token != historySearchGeneration) return;
                    List<Integer> completed = matches; String error = failure;
                    main.post(() -> {
                        if (destroyed || token != historySearchGeneration || historyDialog == null || !historyDialog.isShowing()
                                || !scope.equals(draftKey(detailPeer))) return;
                        found.clear(); found.addAll(completed); shown[0] = HistorySearch.PAGE_SIZE;
                        status.setText(error.isEmpty() ? "找到 " + found.size() + " 条本地记录" : error);
                        display[0].run(); resultScroll.scrollTo(0, 0);
                    });
                }); } catch (RejectedExecutionException ignored) { }
            };
            main.postDelayed(pending[0], 180);
        };
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { search.run(); }
            public void afterTextChanged(Editable s) { }
        };
        query.addTextChangedListener(watcher); from.addTextChangedListener(watcher); to.addTextChangedListener(watcher);
        historyDialog = new AlertDialog.Builder(this).setTitle("查找聊天记录 · " + peer)
                .setView(fields).setNegativeButton("关闭", null).create();
        historyDialog.setOnDismissListener(d -> { historySearchGeneration++; });
        showInputDialog(historyDialog);
        search.run();
    }

    private String historyPreview(JSONObject item) { return HistorySearch.preview(item); }

    private void jumpToMessage(JSONObject target) {
        List<JSONObject> history = messageIndex.messages(detailPeer);
        int index = -1;
        String targetKey = null;
        for (int i = 0; i < history.size(); i++) {
            JSONObject item = history.get(i);
            if (item == target || (!target.optString("clientId").isEmpty()
                    && target.optString("clientId").equals(item.optString("clientId"))
                    && target.optString("sender").equals(item.optString("sender")))) { index = i; break; }
            if (targetKey == null) targetKey = MessageIndex.rowKey(target);
            if (targetKey.equals(messageIndex.rowKey(detailPeer, i))) { index = i; break; }
        }
        if (index < 0 || conversationScroll == null) return;
        revealedHistoryIndex = index;
        historyWindow = HistoryWindow.around(history.size(), index);
        updateConversationStream(false);
        final String key = messageViewKey(index);
        conversationAtBottom = false;
        conversationScroll.post(() -> {
            View view = messageViews.get(key);
            if (view != null) {
                conversationScroll.scrollTo(0, Math.max(0, view.getTop() - dp(20)));
                view.setBackgroundColor(Color.rgb(255, 243, 190));
                main.postDelayed(() -> view.setBackgroundColor(Color.TRANSPARENT), 1800);
            }
        });
    }

    private void renderSettings() {
        ScrollView scroll = scroll();
        LinearLayout body = padded();
        scroll.addView(body);
        body.addView(section("服务器"));
        JSONArray servers = state.optJSONArray("servers");
        if (servers != null) for (int i = 0; i < servers.length(); i++) {
            JSONObject item = servers.optJSONObject(i);
            String address = item == null ? servers.optString(i) : item.optString("address");
            if (address.isEmpty()) continue;
            LinearLayout line = row();
            line.addView(rowItem(address, address.equals(server()) ? "当前服务器" : "点击切换",
                    () -> switchServer(address)), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(button("移除", 13, MUTED, v -> confirmRemove(address)));
            body.addView(line);
        }
        body.addView(space(14));
        body.addView(primary("添加服务器", v -> promptServer()), new LinearLayout.LayoutParams(-1, dp(48)));
        if (signedIn()) {
            body.addView(space(24));
            body.addView(section("当前账号"));
            body.addView(rowItem(state.optString("username"), state.optString("status"), null));
            accountDeadlineView = hint(accountDeadlineText());
            body.addView(accountDeadlineView);
            body.addView(hint("成功连接服务器会续期；离线时显示的是上次查询的预计期限。"));
            body.addView(space(15));
            body.addView(secondary("退出登录", v -> {
                detailPeer = "";
                loginUser = "";
                draft = "";
                page = "messages";
                if (locationSharing != null) locationSharing.stopLive();
                controller.logout();
            }), new LinearLayout.LayoutParams(-1, dp(48)));
        }
        body.addView(space(24));
        body.addView(section("更新"));
        body.addView(hint("当前版本 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）。服务器安装包会核对大小、SHA256、包名、版本码和签名；安装由系统确认，应用数据会保留。"));
        body.addView(secondary("检查当前服务器更新", v -> checkServerUpdate()), new LinearLayout.LayoutParams(-1, dp(48)));
        if (!updateMessage.isEmpty() && server().equals(updateServer)) {
            body.addView(hint(updateMessage));
            if (updatePercent >= 0 && !updateReady) body.addView(hint("下载进度：" + updatePercent + "%"));
        }
        if (updateRelease != null && server().equals(updateServer)) {
            if (updateReady) body.addView(primary("安装已验证的更新包", v -> installServerUpdate()), new LinearLayout.LayoutParams(-1, dp(48)));
            else if ("downloading".equals(updatePhase))
                body.addView(secondary("取消下载", v -> cancelServerUpdate()), new LinearLayout.LayoutParams(-1, dp(48)));
            else body.addView(primary("下载 " + updateRelease.version, v -> downloadServerUpdate()), new LinearLayout.LayoutParams(-1, dp(48)));
        }
        body.addView(space(8));
        body.addView(secondary("查看官方最新版本", v -> {
            Intent link = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/wobuyaoquminga/chatapp-secure/releases/latest"));
            try { startActivity(link); }
            catch (RuntimeException error) { Toast.makeText(this, "无法打开更新页面", Toast.LENGTH_LONG).show(); }
        }), new LinearLayout.LayoutParams(-1, dp(48)));
        body.addView(space(24));
        body.addView(section("使用说明"));
        addGuideItem(body, "开始使用", "首次打开先添加服务器地址，再注册账号或输入密码登录。可在这里保存、切换多个服务器；切换后需登录该服务器的账号。");
        addGuideItem(body, "密码、本机密钥与重新登录", "密码用于登录。私钥和已解密的历史保存在本机，卸载或清除应用数据后无法恢复。只要服务器账号尚未被清理，仍可凭原用户名和密码登录；本机密钥丢失或换设备时会重建加密身份，旧设备会退出。联系人会收到身份更新提示，重新核对并确认安全码后才能继续发送；新身份无法解密旧身份的历史密文。");
        addGuideItem(body, "服务器保存的内容", "服务器仍保存用户名、密码验证信息、公钥和密文消息，直到相应数据按保留规则被清理。服务器不保存本机私钥，也无法解密聊天记录。");
        addGuideItem(body, "开始聊天", "在“消息”页点＋输入对方用户名。首次只能先发一条消息，等对方在“联系人”页同意后才能继续聊天。");
        addGuideItem(body, "联系人与会话", "删除联系人会撤销双方聊天许可，原聊天记录仍保留；再次发消息需要重新同意。“清除”只把会话从列表隐藏，新消息到来或重新打开时会显示。");
        addGuideItem(body, "在线、接收与离线", "显示“在线”表示已连上服务器。“已送达对方设备”表示收到接收确认，不代表对方已阅读。离线时会自动尝试重连；重新连上后领取离线消息。系统强制停止应用或停止进程后，无法保证新消息和来电提醒；请重新打开应用并确认在线。");
        addGuideItem(body, "核对安全码", "打开聊天中的“安全码”，通过其他可信渠道与对方逐位核对；一致后再确认身份。对方重建身份后旧确认失效，需要重新核对；确认前会暂停向对方发送消息。");
        addGuideItem(body, "7 天未连接清理", "连续 7 天没有成功认证并连上服务器，服务器会删除该账号、服务器保存的公钥和密文消息。登录后显示“在线”才算成功连接；仅打开应用但连接失败不算。本机历史和私钥可能仍在，但无法从服务器恢复已删除的数据。");
        content.addView(scroll);
    }

    private void checkServerUpdate() {
        String address = server();
        if (address.isEmpty() || serverUpdates == null) {
            Toast.makeText(this, "请先选择服务器", Toast.LENGTH_LONG).show();
            return;
        }
        updateServer = address; updateMessage = "正在检查当前服务器…";
        updateRelease = null; updateReady = false; updatePercent = -1; updatePhase = "";
        render();
        serverUpdates.check(address);
    }

    private void downloadServerUpdate() {
        if (serverUpdates == null || updateRelease == null || !server().equals(updateServer)) return;
        updateMessage = "正在连接下载…"; updatePercent = 0; updatePhase = "downloading";
        render();
        serverUpdates.download(updateServer);
    }

    private void cancelServerUpdate() {
        if (serverUpdates != null) serverUpdates.cancel();
        updateMessage = "下载已取消"; updateRelease = null; updateReady = false;
        updatePercent = -1; updatePhase = "";
        render();
    }

    private void installServerUpdate() {
        if (!updateReady || updateRelease == null || serverUpdates == null || !server().equals(updateServer)) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            updateMessage = "请在系统设置中允许此应用安装更新包，然后返回点击安装。";
            render();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException error) {
                Toast.makeText(this, "请在系统设置中允许此应用安装未知应用", Toast.LENGTH_LONG).show();
            }
            return;
        }
        final String address = updateServer;
        final UpdatePolicy.Release release = updateRelease;
        updateMessage = "正在重新核验安装包…";
        render();
        serverUpdates.verifyForInstall(address, release, () -> main.post(() -> {
            if (destroyed || !address.equals(server()) || !updateReady || updateRelease != release) return;
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(UpdateInstallProvider.uri(this), "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(install); }
            catch (RuntimeException error) { Toast.makeText(this, "无法打开系统安装界面", Toast.LENGTH_LONG).show(); }
        }));
    }

    private String accountDeadlineText() {
        long expiry = accountDeadlineMillis();
        return expiry <= 0 ? "暂无法查询账号清理期限；请连接支持查询的服务器。"
                : deadlineText(expiry, state.optBoolean("online"));
    }

    private long accountDeadlineMillis() {
        long expiry = state.optLong("accountExpiresAtLocal", 0);
        if (expiry <= 0) {
            AccountStatusCache.Deadline cached = AccountStatusCache.load(this,
                    server(), state.optString("username"));
            expiry = cached == null ? 0 : cached.localExpiryMillis;
        }
        return expiry;
    }

    private static String deadlineText(long expiry, boolean online) {
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) return "按上次服务器报告，账号可能已到期；请连接服务器核实。";
        long hours = (remaining + 3599999L) / 3600000L;
        String time = hours < 24 ? hours + " 小时" : (hours / 24) + " 天 " + (hours % 24) + " 小时";
        return "预计距账号清理还有 " + time + (online ? "；保持连接会续期。" : "；离线估算，请重新连接核实。");
    }

    private void addGuideItem(LinearLayout body, String title, String description) {
        LinearLayout card = column();
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setBackground(rounded(Color.WHITE, 11, BORDER));
        card.addView(text(title, 15, true, INK));
        TextView detail = text(description, 14, false, MUTED);
        detail.setPadding(0, dp(6), 0, 0);
        detail.setLineSpacing(dp(3), 1f);
        card.addView(detail);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(9));
        body.addView(card, params);
    }

    private void renderTabs() {
        tab("消息", "messages");
        tab("联系人", "contacts");
        tab("设置", "settings");
    }

    private void tab(String title, String destination) {
        boolean active = page.equals(destination) && detailPeer.isEmpty();
        TextView item = button(title, 14, active ? GREEN : MUTED, v -> {
            saveDraft();
            detailPeer = "";
            page = destination;
            render();
        });
        if (active) item.setTypeface(null, Typeface.BOLD);
        tabs.addView(item, new LinearLayout.LayoutParams(0, dp(53), 1));
    }

    private void openPeer(String peer) { openPeer(peer, false); }

    void requestCallPermissions(String mode) {
        if (call == null || controller == null) return;
        if (call.busy()) {
            pendingCallPeer = call.session().peer;
            pendingCallMode = call.session().mode;
            pendingCallContext = call.context();
        } else {
            if (callPreparing) return;
            if (!"accepted".equals(relation(detailPeer)) || isDeleted(detailPeer) || identityChanged(detailPeer)
                    || !state.optBoolean("online")) {
                Toast.makeText(this, "请先连接服务器并与已接受的联系人通话", Toast.LENGTH_LONG).show();
                return;
            }
            pendingCallPeer = detailPeer;
            pendingCallMode = mode;
            pendingCallContext = controller.locationContext();
        }
        if (callPermissionsGranted(pendingCallMode)) beginPermittedCall();
        else requestPermissions(pendingCallMode.equals("video")
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}
                : new String[]{Manifest.permission.RECORD_AUDIO}, CALL_PERMISSION);
    }

    private boolean callPermissionsGranted(String mode) {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && (!"video".equals(mode) || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
    }

    private void beginPermittedCall() {
        String peer = pendingCallPeer, mode = pendingCallMode;
        pendingCallPeer = pendingCallMode = "";
        if (peer.isEmpty() || pendingCallContext != controller.locationContext()) return;
        if (call.busy()) controller.prepareIncomingCall(call.session(), call.context());
        else {
            callPreparing = true;
            controller.prepareCall(peer, mode);
        }
    }

    @Override public void onCallReady(CallSession session, JSONArray iceServers, long context) {
        callPreparing = false;
        if (destroyed || call == null) return;
        if (session.outgoing && call.busy()) return;
        if (!session.outgoing && call.session() != session) return;
        if (!foreground) {
            pendingReadyCall = session;
            pendingReadyIce = iceServers;
            pendingReadyContext = context;
            return;
        }
        call.start(session, iceServers, context);
    }

    @Override public void onCall(JSONObject frame, long context) {
        if (destroyed || call == null) return;
        if ("call_error".equals(frame.optString("type"))) { call.error(frame); return; }
        String action = frame.optString("action");
        if (action.equals("offer")) {
            try {
                CallSession incoming = new CallSession(frame.getString("callId"), frame.getString("from"),
                        frame.getString("fromAccountId"), frame.getString("mode"), false);
                if (call.busy() || callPreparing || pendingReadyCall != null) controller.sendCall(incoming, "busy", null, context);
                else call.ring(incoming, CallSession.parseSdpPayload("offer", frame.getString("payload")), context, foreground);
            } catch (Exception ignored) { }
        } else call.signal(frame);
    }

    @Override public void onCallContextLost() {
        callPreparing = false;
        pendingCallPeer = pendingCallMode = "";
        pendingReadyCall = null;
        pendingReadyIce = null;
        if (call != null) call.finish("连接已断开，通话结束", false, null);
    }

    private void openPeer(String peer, boolean reopen) {
        callToolsExpanded = false;
        saveDraft();
        detailPeer = peer;
        draft = drafts.getOrDefault(draftKey(peer), "");
        historyWindow = HistoryWindow.latest(messageIndex.messages(peer).size());
        revealedHistoryIndex = -1;
        conversationAtBottom = true;
        conversationScrollY = 0;
        render();
        controller.openPeer(peer, reopen);
    }

    private void promptPeer() {
        EditText name = field("联系人用户名", InputType.TYPE_CLASS_TEXT);
        name.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("打开会话").setView(scrollDialog(dialogWrap(name)))
                .setNegativeButton("取消", null).setPositiveButton("打开", (d, w) -> {
                    String peer = name.getText().toString().trim();
                    if (!Usernames.valid(peer) || peer.equals(state.optString("username"))) {
                        showError("请输入另一位有效用户的用户名");
                        return;
                    }
                    openPeer(peer, true);
                }).create();
        showInputDialog(dialog);
    }

    private void promptServer() {
        EditText address = field("https://chat.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        CheckBox test = new CheckBox(this);
        test.setText("允许局域网 HTTP 测试（明文传输）");
        test.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
        test.setChecked(lanTest);
        LinearLayout fields = dialogWrap(address);
        fields.addView(test);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加服务器").setView(scrollDialog(fields))
                .setNegativeButton("取消", null).setPositiveButton("保存并选择", (d, w) -> {
                    String value = address.getText().toString().trim();
                    if (value.isEmpty()) { showError("请输入服务器地址"); return; }
                    lanTest = test.isChecked();
                    loginUser = "";
                    page = "messages";
                    resetServerUpdate();
                    if (locationSharing != null) locationSharing.stopLive();
                    controller.setServer(value, lanTest);
                }).create();
        showInputDialog(dialog);
    }

    private void switchServer(String address) {
        resetServerUpdate();
        detailPeer = "";
        loginUser = "";
        draft = "";
        page = "messages";
        if (locationSharing != null) locationSharing.stopLive();
        controller.setServer(address, lanTest);
    }

    private void confirmRemove(String address) {
        new AlertDialog.Builder(this).setTitle("移除服务器？")
                .setMessage("将从服务器列表移除此地址及本机保存的账号入口。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (d, w) -> {
                    if (address.equals(server())) resetServerUpdate();
                    controller.removeServer(address);
                }).show();
    }

    private void resetServerUpdate() {
        if (serverUpdates != null) serverUpdates.cancel();
        updateRelease = null;
        updateServer = "";
        updateMessage = "";
        updatePercent = -1;
        updateReady = false;
        updatePhase = "";
    }

    private JSONArray accounts() {
        JSONArray servers = state.optJSONArray("servers");
        if (servers != null) for (int i = 0; i < servers.length(); i++) {
            JSONObject entry = servers.optJSONObject(i);
            if (entry != null && server().equals(entry.optString("address"))) {
                JSONArray result = entry.optJSONArray("accounts");
                if (result != null) return result;
            }
        }
        return new JSONArray();
    }

    private String relation(String peer) {
        JSONObject relations = state.optJSONObject("relationships");
        JSONObject item = relations == null ? null : relations.optJSONObject(peer);
        return item == null ? "" : item.optString("status");
    }

    private boolean isDeleted(String peer) {
        JSONObject deleted = state.optJSONObject("deletedPeers");
        return deleted != null && deleted.has(peer);
    }

    private String peerStatus(String peer) {
        if (isDeleted(peer)) return "该用户已销户";
        if (identityChanged(peer)) return "身份已更新 · 待核对安全码";
        JSONObject relations = state.optJSONObject("relationships");
        JSONObject item = relations == null ? null : relations.optJSONObject(peer);
        if (item == null) return "在线状态未知";
        String status = item.optString("status");
        String presence = item.optBoolean("online") ? "在线" : "离线";
        if (status.equals("pending_incoming")) return presence + " · 待你同意";
        if (status.equals("pending_outgoing")) return presence + " · 等待同意";
        return presence;
    }

    private boolean identityChanged(String peer) {
        JSONObject changes = state.optJSONObject("identityChanges");
        return changes != null && changes.has(peer);
    }

    private boolean matchesNormalized(String name, String normalizedQuery) {
        return name.toLowerCase(java.util.Locale.ROOT).contains(normalizedQuery);
    }

    private interface QueryChanged { void onQuery(String query); }

    private void addSearch(LinearLayout body, String hint, String current, QueryChanged listener) {
        EditText search = field(hint, InputType.TYPE_CLASS_TEXT);
        search.setBackground(rounded(Color.rgb(238, 243, 240), 14, Color.rgb(238, 243, 240)));
        search.setSingleLine(true);
        search.setText(current);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
        params.setMargins(dp(16), dp(12), dp(16), dp(10));
        body.addView(search, params);
        search.addTextChangedListener(new TextWatcher() {
            private Runnable pending;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pending != null) main.removeCallbacks(pending);
                String query = s.toString();
                pending = () -> {
                    if (search.isAttachedToWindow()) listener.onQuery(query);
                };
                main.postDelayed(pending, 120);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void confirmRemoveContact(String peer) {
        new AlertDialog.Builder(this).setTitle("删除联系人？")
                .setMessage("解除与 " + peer + " 的联系人关系，双方聊天记录仍会保留。再次发送消息需对方重新同意。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> controller.removeContact(peer)).show();
    }

    private void confirmClearConversation(String peer) {
        new AlertDialog.Builder(this).setTitle("清除会话？")
                .setMessage("将 " + peer + " 从会话列表移除。聊天记录仍保留，新消息到来或重新打开时会显示。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, w) -> controller.clearConversation(peer)).show();
    }

    @Override public void onState(JSONObject snapshot, String error) {
        final long generation = ++snapshotGeneration;
        JSONObject next = snapshot == null ? new JSONObject() : snapshot;
        try {
            uiPreparation.execute(() -> {
                if (destroyed || generation != snapshotGeneration) return;
                MessageIndex prepared = MessageIndex.build(next, messageIndex);
                main.post(() -> {
                    if (!destroyed && generation == snapshotGeneration) acceptState(next, error, prepared);
                });
            });
        } catch (RejectedExecutionException ignored) { /* Activity is closing. */ }
    }

    private String headerKey() {
        return screenKey() + ':' + state.optBoolean("online") + ':' + state.optString("uiError") + ':' + peerStatus(detailPeer) + ':' + state.optJSONObject("locationSharing");
    }

    private String bodyKey() {
        if (!detailPeer.isEmpty()) return relation(detailPeer) + ':' + isDeleted(detailPeer) + ':' + identityChanged(detailPeer);
        if (page.equals("contacts") && signedIn()) return messageIndex.contactRows;
        if (page.equals("messages") && signedIn()) return messageIndex.messageRows;
        if (page.equals("settings")) return state.optString("status") + ':' + state.optJSONArray("servers")
                + ':' + state.optLong("accountExpiresAtLocal", 0);
        // Background connection notices must not recreate username/password fields.
        return String.valueOf(state.optJSONArray("servers"));
    }

    private void acceptState(JSONObject snapshot, String error, MessageIndex prepared) {
        boolean wasSigned = signedIn();
        boolean wasOnline = state.optBoolean("online");
        String previousUsername = state.optString("username");
        String previousServer = server();
        String oldScreen = screenKey();
        String oldHeader = headerKey();
        String oldBody = bodyKey();
        String oldHistory = detailPeer.isEmpty() ? "" : visibleHistoryKey();
        boolean readingHistory = !detailPeer.isEmpty() && (historyWindow.hasNewer(messageIndex.messages(detailPeer).size())
                || conversationScroll != null && conversationStream != null
                && conversationScroll.getScrollY() + conversationScroll.getHeight() < conversationStream.getHeight() - dp(48));
        JSONObject oldChanges = state.optJSONObject("identityChanges");
        String oldSafetyChange = oldChanges == null ? "" : String.valueOf(oldChanges.opt(safetyPeer));
        state = snapshot == null ? new JSONObject() : snapshot;
        if (!previousServer.equals(server())) {
            if (serverUpdates != null) serverUpdates.cancel();
            updateRelease = null; updateMessage = ""; updateServer = ""; updatePercent = -1; updateReady = false; updatePhase = "";
        }
        if (foreground && !wasSigned && signedIn()) { CallNotifier.ensurePermission(this); openNotifiedConversation(); }
        if (call != null && call.busy()) {
            CallSession active = call.session();
            JSONObject relations = state.optJSONObject("relationships");
            JSONObject relation = relations == null ? null : relations.optJSONObject(active.peer);
            JSONObject deletedPeers = state.optJSONObject("deletedPeers");
            JSONObject changes = state.optJSONObject("identityChanges");
            JSONObject accountIds = state.optJSONObject("peerAccountIds");
            if (!state.optBoolean("online") || !state.optString("username").equals(previousUsername)
                    || !server().equals(previousServer) || relation == null
                    || !"accepted".equals(relation.optString("status"))
                    || deletedPeers != null && deletedPeers.has(active.peer)
                    || changes != null && changes.has(active.peer)
                    || accountIds == null || !active.accountId.equals(accountIds.optString(active.peer))
                    || call.context() != controller.locationContext())
                call.finish("通话已结束", false, null);
        }
        if (error != null && !error.isEmpty()) callPreparing = false;
        messageIndex = prepared;
        if (!detailPeer.isEmpty()) historyWindow = readingHistory
                ? historyWindow.retain(messageIndex.messages(detailPeer).size())
                : HistoryWindow.latest(messageIndex.messages(detailPeer).size());
        if (error != null && !error.isEmpty()) try { state.put("uiError", error); } catch (Exception ignored) { }
        if ((!wasSigned && signedIn()) || !previousServer.equals(server()) || wasSigned && !signedIn()
                || !previousUsername.equals(state.optString("username"))) {
            if (locationSharing != null) locationSharing.stopLive();
            pendingLocationPeer = pendingLocationAction = "";
            historySearchGeneration++;
            if (historyDialog != null) { historyDialog.dismiss(); historyDialog = null; }
            loginUser = "";
            detailPeer = "";
            draft = "";
            historyWindow = HistoryWindow.latest(0);
            revealedHistoryIndex = -1;
            page = "messages";
        }
        if (locationSharing != null && wasOnline && !state.optBoolean("online")) locationSharing.stopLive();
        JSONObject newChanges = state.optJSONObject("identityChanges");
        String newSafetyChange = newChanges == null ? "" : String.valueOf(newChanges.opt(safetyPeer));
        if (safetyDialog != null && (isDeleted(safetyPeer) || !oldSafetyChange.equals(newSafetyChange)
                || !previousUsername.equals(state.optString("username")) || !previousServer.equals(server()))) {
            safetyDialog.dismiss();
            safetyDialog = null;
            safetyPeer = "";
        }
        if (!oldScreen.equals(screenKey()) || !oldBody.equals(bodyKey()) &&
                (messageResults == null && contactResults == null || !detailPeer.isEmpty())) {
            render();
            return;
        }
        if (!oldHeader.equals(headerKey())) {
            header.removeAllViews();
            renderHeader(signedIn());
        }
        if (!oldBody.equals(bodyKey())) {
            if (messageResults != null) populateMessages(messageResults);
            if (contactResults != null) populateContacts(contactResults);
        }
        if (conversationStream != null && (!oldHistory.equals(visibleHistoryKey())
                || wasOnline != state.optBoolean("online"))) updateConversationStream(true);
        if (conversationStatus != null) conversationStatus.setText(state.optString("status"));
    }

    private AlertDialog safetyDialog;
    private String safetyPeer = "";

    @Override public void onSafety(String peer, JSONObject result) {
        if (isDeleted(peer)) return;
        if (safetyDialog != null) safetyDialog.dismiss();
        safetyPeer = peer;
        String code = result.optString("code");
        String spaced = code.replaceAll("(.{5})", "$1 ").trim();
        boolean verified = result.optBoolean("verified");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(peer + " 的安全码")
                .setMessage(spaced + "\n\n请通过其他渠道与对方逐位核对。" +
                        (verified ? "\n已确认此身份。" : "\n尚未确认此身份。"))
                .setNegativeButton("关闭", null);
        if (!verified) dialog.setPositiveButton("已核对并确认", (d, w) ->
                controller.safety(peer, true, code));
        safetyDialog = dialog.show();
    }

    @Override public void onSent(String draftKey, String body, long draftRevision) {
        if (draftRevisions.getOrDefault(draftKey, 0L) != draftRevision) return;
        if (body.equals(drafts.get(draftKey))) drafts.remove(draftKey);
        if (composer != null && draftKey.equals(composerDraftKey)
                && body.equals(composer.getText().toString())) {
            draft = "";
            composer.setText("");
        }
    }

    private void saveDraft() {
        if (composer != null) {
            draft = composer.getText().toString();
            if (!composerDraftKey.isEmpty()) drafts.put(composerDraftKey, draft);
            composer = null;
        }
    }

    private String draftKey(String peer) { return server() + '\0' + state.optString("username") + '\0' + peer; }

    private void showError(String message) {
        try { state.put("uiError", message); } catch (Exception ignored) { }
        render();
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private LinearLayout dialogWrap(View child) {
        LinearLayout box = column();
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(child);
        return box;
    }

    private ScrollView scrollDialog(View fields) {
        ScrollView scroll = scroll();
        scroll.addView(fields);
        return scroll;
    }

    private void showInputDialog(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private LinearLayout padded() {
        LinearLayout value = column();
        value.setPadding(dp(20), dp(24), dp(20), dp(30));
        return value;
    }

    private ScrollView scroll() {
        ScrollView value = new ScrollView(this);
        value.setFillViewport(true);
        return value;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(null, Typeface.BOLD);
        return label;
    }

    private TextView section(String value) {
        TextView label = text(value, 19, true, INK);
        label.setPadding(0, 0, 0, dp(15));
        return label;
    }

    private TextView hint(String value) {
        TextView label = text(value, 13, false, MUTED);
        label.setPadding(0, dp(5), 0, dp(8));
        return label;
    }

    private TextView button(String value, int size, int color, View.OnClickListener click) {
        TextView label = text(value, size, false, color);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(9), dp(7), dp(9), dp(7));
        label.setBackground(interactiveBackground(Color.TRANSPARENT, 11, Color.TRANSPARENT));
        label.setOnClickListener(click);
        return label;
    }

    private TextView primary(String value, View.OnClickListener click) {
        TextView label = button(value, 16, Color.WHITE, click);
        label.setTypeface(null, Typeface.BOLD);
        label.setBackground(interactiveBackground(GREEN, 11, GREEN));
        return label;
    }

    private TextView secondary(String value, View.OnClickListener click) {
        TextView label = button(value, 16, INK, click);
        label.setBackground(interactiveBackground(Color.WHITE, 11, BORDER));
        return label;
    }

    private EditText field(String hint, int type) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTag(hint);
        field.setTextSize(16);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setInputType(type);
        field.setPadding(dp(13), dp(10), dp(13), dp(10));
        field.setBackground(rounded(Color.WHITE, 14, BORDER));
        return field;
    }

    private TextView readonly(String value) {
        TextView label = text(value, 16, false, INK);
        label.setPadding(dp(13), dp(13), dp(13), dp(13));
        label.setBackground(rounded(Color.WHITE, 14, BORDER));
        return label;
    }

    private View rowItem(String title, String subtitle, Runnable click) {
        LinearLayout line = row();
        line.setPadding(dp(14), dp(12), dp(12), dp(12));
        line.setBackground(interactiveBackground(Color.WHITE, 15, Color.WHITE));
        line.setMinimumHeight(dp(66));
        TextView avatar = text(title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT), 18, true, Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(rounded(Color.rgb(55, 137, 97), 21, Color.rgb(55, 137, 97)));
        line.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout labels = column();
        labels.setPadding(dp(13), 0, dp(8), 0);
        labels.addView(text(title, 16, true, INK));
        TextView sub = text(subtitle, 13, false, MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(sub);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (click != null) {
            line.addView(text("›", 23, false, MUTED));
            line.setOnClickListener(v -> click.run());
        }
        return line;
    }

    private View empty(String title, String detail) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(80), dp(24), dp(40));
        TextView heading = text(title, 18, true, INK);
        heading.setGravity(Gravity.CENTER);
        box.addView(heading);
        TextView message = hint(detail);
        message.setGravity(Gravity.CENTER);
        box.addView(message);
        return box;
    }

    private View space(int height) {
        View value = new View(this);
        value.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return value;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private RippleDrawable interactiveBackground(int fill, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(Color.rgb(205, 226, 215)),
                rounded(fill, radius, stroke), rounded(Color.WHITE, radius, Color.WHITE));
    }

    private boolean isLoopback(String address) {
        return address.startsWith("http://localhost") || address.startsWith("http://127.0.0.1") ||
                address.startsWith("http://[::1]");
    }

    private int dp(int size) { return (int) (size * getResources().getDisplayMetrics().density + 0.5f); }
}

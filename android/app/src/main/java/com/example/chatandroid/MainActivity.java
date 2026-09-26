package com.example.chatandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Native account and conversation screens driven by ChatController snapshots. */
public final class MainActivity extends Activity implements ChatController.Listener {
    private static final int INK = Color.rgb(29, 39, 42);
    private static final int MUTED = Color.rgb(106, 117, 119);
    private static final int GREEN = Color.rgb(7, 166, 96);
    private static final int BG = Color.rgb(246, 248, 247);
    private static final int BORDER = Color.rgb(228, 233, 230);
    private ChatController controller;
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

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout shell = column();
        shell.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            shell.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                shell.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
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
        render();
        controller = new ChatController(this, this);
    }

    @Override protected void onDestroy() {
        if (controller != null) controller.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
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
        if (restoreFocus && composer != null) {
            composer.requestFocus();
            composer.setSelection(Math.min(Math.max(cursor, 0), composer.length()));
        }
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
            if (!user.isEmpty()) body.addView(rowItem(user, "输入密码登录", () -> { loginUser = user; render(); }));
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
                    "2. 账号身份绑定本机应用数据。清除应用数据或卸载软件后，本机账号和聊天历史无法恢复；密码和服务器均无法找回本机密钥。",
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
        TextView security = hint("本地密钥只保存在本设备。已有账号若绑定其他设备，请注册新账号。");
        security.setPadding(0, dp(18), 0, 0);
        body.addView(security);
        content.addView(scroll);
    }

    private void renderMessages() {
        ScrollView scroll = scroll();
        LinearLayout body = column();
        scroll.addView(body);
        LinearLayout results = column();
        addSearch(body, "搜索会话", messageQuery, query -> {
            messageQuery = query;
            populateMessages(results);
        });
        body.addView(results);
        populateMessages(results);
        content.addView(scroll);
    }

    private void populateMessages(LinearLayout results) {
        results.removeAllViews();
        List<String> peers = conversationNames();
        Map<String, JSONObject> last = latestMessages();
        Collections.sort(peers, (a, b) -> time(last.get(b)).compareTo(time(last.get(a))));
        int shown = 0;
        for (String peer : peers) {
            if (!matches(peer, messageQuery)) continue;
            JSONObject message = last.get(peer);
            String preview = message == null ? "点击开始聊天" : message.optString("body", "消息");
            LinearLayout line = row();
            line.setBackgroundColor(Color.WHITE);
            line.addView(rowItem(peer, peerStatus(peer) + " · " + preview,
                    () -> openPeer(peer)), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(button("清除", 13, MUTED, v -> confirmClearConversation(peer)));
            results.addView(line);
            shown++;
        }
        if (shown == 0) results.addView(empty(messageQuery.isEmpty() ? "还没有会话" : "没有匹配的会话",
                messageQuery.isEmpty() ? "点击右上角＋，输入用户名开始聊天。" : "试试其他用户名。"));
    }

    private void renderContacts() {
        ScrollView scroll = scroll();
        LinearLayout body = column();
        scroll.addView(body);
        LinearLayout results = column();
        addSearch(body, "搜索联系人", contactQuery, query -> {
            contactQuery = query;
            populateContacts(results);
        });
        body.addView(results);
        populateContacts(results);
        content.addView(scroll);
    }

    private void populateContacts(LinearLayout results) {
        results.removeAllViews();
        List<String> contacts = contactNames();
        int shown = 0;
        for (String peer : contacts) {
            if (!matches(peer, contactQuery)) continue;
            LinearLayout line = row();
            line.setBackgroundColor(Color.WHITE);
            line.addView(rowItem(peer, peerStatus(peer), () -> openPeer(peer)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(button("删除", 13, MUTED, v -> confirmRemoveContact(peer)));
            results.addView(line);
            shown++;
        }
        JSONObject relations = state.optJSONObject("relationships");
        if (relations != null) for (java.util.Iterator<String> names = relations.keys(); names.hasNext();) {
            String peer = names.next();
            if (!"pending_incoming".equals(relation(peer)) || !matches(peer, contactQuery)) continue;
            LinearLayout line = row();
            line.setBackgroundColor(Color.WHITE);
            line.addView(rowItem(peer, "请求与你聊天 · " + peerStatus(peer), () -> openPeer(peer)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(button("同意", 13, GREEN, v -> controller.acceptContact(peer)));
            results.addView(line);
            shown++;
        }
        if (shown == 0) results.addView(empty(contactQuery.isEmpty() ? "暂无联系人" : "没有匹配的联系人",
                contactQuery.isEmpty() ? "同意聊天请求后，对方会出现在这里。" : "试试其他用户名。"));
    }

    private void renderConversation() {
        LinearLayout body = column();
        String relation = relation(detailPeer);
        boolean deleted = isDeleted(detailPeer);
        if (deleted) body.addView(hint("该用户已销户，无法向不存在的账号发送消息。历史记录仍保留。"));
        if (relation.equals("pending_incoming")) {
            LinearLayout request = row();
            request.setPadding(dp(16), dp(10), dp(16), dp(10));
            request.setBackgroundColor(Color.rgb(232, 247, 237));
            request.addView(text("对方请求与你聊天", 14, false, INK),
                    new LinearLayout.LayoutParams(0, -2, 1));
            request.addView(button("同意", 15, GREEN, v -> controller.acceptContact(detailPeer)));
            body.addView(request);
        } else if (relation.equals("pending_outgoing")) {
            TextView pending = hint("首条消息已发出，等待对方同意后可继续聊天。");
            pending.setPadding(dp(16), dp(8), dp(16), dp(8));
            body.addView(pending);
        }
        ScrollView scroll = scroll();
        conversationScroll = scroll;
        LinearLayout stream = column();
        stream.setPadding(dp(14), dp(10), dp(14), dp(14));
        scroll.addView(stream);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        JSONArray messages = state.optJSONArray("messages");
        int count = 0;
        if (messages != null) for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null || !detailPeer.equals(item.optString("sender")) &&
                    !detailPeer.equals(item.optString("recipient"))) continue;
            boolean outgoing = state.optString("username").equals(item.optString("sender"));
            LinearLayout bubble = column();
            bubble.setPadding(dp(13), dp(9), dp(13), dp(9));
            bubble.setBackground(rounded(outgoing ? Color.rgb(214, 246, 220) : Color.WHITE, 14, BORDER));
            bubble.addView(text(item.optString("body"), 16, false, INK));
            TextView sentTime = text(MessageTime.format(item.optString("createdAt", "")), 11, false, MUTED);
            sentTime.setPadding(0, dp(5), 0, 0);
            bubble.addView(sentTime);
            String status = item.optString("status");
            if (outgoing && !status.isEmpty()) {
                TextView delivery = text(status, 11, false, MUTED);
                delivery.setPadding(0, dp(5), 0, 0);
                bubble.addView(delivery);
            }
            LinearLayout line = row();
            line.setGravity(outgoing ? Gravity.RIGHT : Gravity.LEFT);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(0, dp(5), 0, dp(5));
            line.addView(bubble, params);
            stream.addView(line);
            count++;
        }
        if (count == 0) stream.addView(empty("暂无消息", "发送第一条端到端加密消息。"));
        LinearLayout tools = row();
        tools.setPadding(dp(16), dp(5), dp(16), dp(5));
        tools.addView(text(state.optString("status"), 12, false, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        if (!deleted) tools.addView(button("安全码  ›", 13, GREEN, v -> controller.safety(detailPeer, false, null)));
        body.addView(tools);
        LinearLayout bar = row();
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(Color.WHITE);
        composer = field("发送消息", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.setText(draft);
        composer.setMaxLines(5);
        composer.setMinHeight(dp(44));
        boolean canSend = !deleted && !relation.startsWith("pending_");
        composer.setEnabled(canSend);
        if (!canSend) composer.setHint(deleted ? "该用户已销户，不能发送" : relation.equals("pending_incoming") ? "同意后可以回复" : "等待对方同意");
        bar.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sendButton = button("发送", 16, canSend ? GREEN : MUTED, v -> {
            String message = composer.getText().toString();
            if (message.trim().isEmpty()) return;
            draft = message;
            controller.send(detailPeer, message);
        });
        sendButton.setEnabled(canSend);
        bar.addView(sendButton, new LinearLayout.LayoutParams(dp(62), dp(44)));
        body.addView(bar);
        content.addView(body);
        boolean atBottom = conversationAtBottom;
        int previousY = conversationScrollY;
        scroll.post(() -> {
            if (atBottom) scroll.fullScroll(View.FOCUS_DOWN);
            else scroll.scrollTo(0, previousY);
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
            body.addView(space(15));
            body.addView(secondary("退出登录", v -> {
                detailPeer = "";
                loginUser = "";
                draft = "";
                page = "messages";
                controller.logout();
            }), new LinearLayout.LayoutParams(-1, dp(48)));
        }
        body.addView(space(24));
        body.addView(section("使用说明"));
        addGuideItem(body, "开始使用", "首次打开先添加服务器地址，再注册账号或输入密码登录。可在这里保存、切换多个服务器；切换后需登录该服务器的账号。");
        addGuideItem(body, "密码与本机密钥", "密码用于登录。此账号的加密聊天身份绑定当前安装；私钥和已解密的聊天记录保存在应用本地数据中。卸载应用或清除应用数据会删除本地私钥和记录，原账号之后无法继续用于加密聊天。密码和服务器都无法找回私钥或已解密的历史；目前不支持账号找回或换设备迁移。");
        addGuideItem(body, "服务器保存的内容", "服务器仍保存用户名、密码验证信息、公钥和密文消息，直到相应数据按保留规则被清理。服务器不保存本机私钥，也无法解密聊天记录。");
        addGuideItem(body, "开始聊天", "在“消息”页点＋输入对方用户名。首次只能先发一条消息，等对方在“联系人”页同意后才能继续聊天。");
        addGuideItem(body, "联系人与会话", "删除联系人会撤销双方聊天许可，原聊天记录仍保留；再次发消息需要重新同意。“清除”只把会话从列表隐藏，新消息到来或重新打开时会显示。");
        addGuideItem(body, "在线、接收与离线", "显示“在线”表示已连上服务器。消息标为“对方客户端已接收”表示收到接收确认，不代表对方已阅读。离线时会自动尝试重连；重新连上后领取离线消息。");
        addGuideItem(body, "核对安全码", "打开聊天中的“安全码”，通过其他可信渠道与对方逐位核对；一致后再确认身份。");
        addGuideItem(body, "7 天未连接清理", "连续 7 天没有成功认证并连上服务器，服务器会删除该账号、服务器保存的公钥和密文消息。登录后显示“在线”才算成功连接；仅打开应用但连接失败不算。本机历史和私钥可能仍在，但无法从服务器恢复已删除的数据。");
        content.addView(scroll);
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

    private void openPeer(String peer, boolean reopen) {
        detailPeer = peer;
        draft = "";
        conversationAtBottom = true;
        conversationScrollY = 0;
        render();
        controller.openPeer(peer, reopen);
    }

    private void promptPeer() {
        EditText name = field("联系人用户名", InputType.TYPE_CLASS_TEXT);
        name.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("打开会话").setView(dialogWrap(name))
                .setNegativeButton("取消", null).setPositiveButton("打开", (d, w) -> {
                    String peer = name.getText().toString().trim();
                    if (!Usernames.valid(peer) || peer.equals(state.optString("username"))) {
                        showError("请输入另一位有效用户的用户名");
                        return;
                    }
                    openPeer(peer, true);
                }).show();
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
        new AlertDialog.Builder(this).setTitle("添加服务器").setView(fields)
                .setNegativeButton("取消", null).setPositiveButton("保存并选择", (d, w) -> {
                    String value = address.getText().toString().trim();
                    if (value.isEmpty()) { showError("请输入服务器地址"); return; }
                    lanTest = test.isChecked();
                    loginUser = "";
                    page = "messages";
                    controller.setServer(value, lanTest);
                }).show();
    }

    private void switchServer(String address) {
        detailPeer = "";
        loginUser = "";
        draft = "";
        page = "messages";
        controller.setServer(address, lanTest);
    }

    private void confirmRemove(String address) {
        new AlertDialog.Builder(this).setTitle("移除服务器？")
                .setMessage("将从服务器列表移除此地址及本机保存的账号入口。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (d, w) -> controller.removeServer(address)).show();
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

    private List<String> contactNames() {
        List<String> result = new ArrayList<>();
        JSONArray names = state.optJSONArray("contacts");
        if (names != null) for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            if (!name.isEmpty() && !result.contains(name)) result.add(name);
        }
        return result;
    }

    private List<String> conversationNames() {
        List<String> result = new ArrayList<>();
        JSONArray names = state.optJSONArray("conversations");
        if (names != null) for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            if (!name.isEmpty() && !result.contains(name)) result.add(name);
        }
        return result;
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
        JSONObject relations = state.optJSONObject("relationships");
        JSONObject item = relations == null ? null : relations.optJSONObject(peer);
        if (item == null) return "在线状态未知";
        String status = item.optString("status");
        String presence = item.optBoolean("online") ? "在线" : "离线";
        if (status.equals("pending_incoming")) return presence + " · 待你同意";
        if (status.equals("pending_outgoing")) return presence + " · 等待同意";
        return presence;
    }

    private boolean matches(String name, String query) {
        return name.toLowerCase(java.util.Locale.ROOT).contains(query.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private interface QueryChanged { void onQuery(String query); }

    private void addSearch(LinearLayout body, String hint, String current, QueryChanged listener) {
        EditText search = field(hint, InputType.TYPE_CLASS_TEXT);
        search.setSingleLine(true);
        search.setText(current);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
        params.setMargins(dp(16), dp(12), dp(16), dp(10));
        body.addView(search, params);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                listener.onQuery(s.toString());
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

    private Map<String, JSONObject> latestMessages() {
        Map<String, JSONObject> result = new HashMap<>();
        JSONArray messages = state.optJSONArray("messages");
        String own = state.optString("username");
        if (messages != null) for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            String peer = own.equals(item.optString("sender")) ? item.optString("recipient") : item.optString("sender");
            if (!peer.isEmpty()) result.put(peer, item);
        }
        return result;
    }

    private String time(JSONObject item) { return item == null ? "" : item.optString("createdAt"); }

    @Override public void onState(JSONObject snapshot, String error) {
        boolean wasSigned = signedIn();
        String previousServer = server();
        state = snapshot == null ? new JSONObject() : snapshot;
        if (error != null && !error.isEmpty()) try { state.put("uiError", error); } catch (Exception ignored) { }
        if ((!wasSigned && signedIn()) || !previousServer.equals(server()) || wasSigned && !signedIn()) {
            loginUser = "";
            detailPeer = "";
            draft = "";
            page = "messages";
        }
        if (safetyDialog != null && isDeleted(safetyPeer)) {
            safetyDialog.dismiss();
            safetyDialog = null;
            safetyPeer = "";
        }
        render();
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

    @Override public void onSent() {
        draft = "";
        if (composer != null) composer.setText("");
    }

    private void saveDraft() {
        if (composer != null) {
            draft = composer.getText().toString();
            composer = null;
        }
    }

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
        label.setOnClickListener(click);
        return label;
    }

    private TextView primary(String value, View.OnClickListener click) {
        TextView label = button(value, 16, Color.WHITE, click);
        label.setTypeface(null, Typeface.BOLD);
        label.setBackground(rounded(GREEN, 11, GREEN));
        return label;
    }

    private TextView secondary(String value, View.OnClickListener click) {
        TextView label = button(value, 16, INK, click);
        label.setBackground(rounded(Color.WHITE, 11, BORDER));
        return label;
    }

    private EditText field(String hint, int type) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(16);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setInputType(type);
        field.setPadding(dp(13), dp(10), dp(13), dp(10));
        field.setBackground(rounded(Color.WHITE, 10, BORDER));
        return field;
    }

    private TextView readonly(String value) {
        TextView label = text(value, 16, false, INK);
        label.setPadding(dp(13), dp(13), dp(13), dp(13));
        label.setBackground(rounded(Color.WHITE, 10, BORDER));
        return label;
    }

    private View rowItem(String title, String subtitle, Runnable click) {
        LinearLayout line = row();
        line.setPadding(dp(18), dp(12), dp(16), dp(12));
        line.setBackgroundColor(Color.WHITE);
        TextView avatar = text(title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(), 18, true, Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(rounded(Color.rgb(102, 175, 143), 10, Color.rgb(102, 175, 143)));
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

    private boolean isLoopback(String address) {
        return address.startsWith("http://localhost") || address.startsWith("http://127.0.0.1") ||
                address.startsWith("http://[::1]");
    }

    private int dp(int size) { return (int) (size * getResources().getDisplayMetrics().density + 0.5f); }
}

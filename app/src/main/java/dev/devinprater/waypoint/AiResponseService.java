package dev.devinprater.waypoint;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public final class AiResponseService extends AccessibilityService {
    private static final String TAG = "Waypoint";
    private static final String GEMINI_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final String CHATGPT_PACKAGE = "com.openai.chatgpt";
    private static final String CLAUDE_PACKAGE = "com.anthropic.claude";
    private static final String GEMINI_RESPONSE_ID = GEMINI_PACKAGE + ":id/assistant_robin_text";
    private static final String GEMINI_MESSAGE_CONTENT_ID = GEMINI_PACKAGE + ":id/assistant_robin_message_content";
    private static final String GEMINI_CODE_BLOCK_ID = GEMINI_PACKAGE + ":id/assistant_robin_code_block";
    private static final String GEMINI_CODE_IMPL_ID = GEMINI_PACKAGE + ":id/assistant_robin_code_impl";
    private static final String GEMINI_STOP_ID = GEMINI_PACKAGE + ":id/assistant_robin_stop_icon_button_compose";
    private static final long SETTLE_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean generating;
    private boolean awaitingCompletion;
    private String lastSpoken = "";
    private String pendingResponse = "";
    private String activePackage = "";

    private final Runnable finishCheck = () -> {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || isGenerating(root)) return;
        String response = findLatestResponse(root);
        if (!response.isEmpty() && response.equals(pendingResponse) && !response.equals(lastSpoken)) {
            announce(response);
            lastSpoken = response;
            awaitingCompletion = false;
        }
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || (!GEMINI_PACKAGE.contentEquals(pkg)
                && !CHATGPT_PACKAGE.contentEquals(pkg)
                && !CLAUDE_PACKAGE.contentEquals(pkg))) return;
        String packageName = pkg.toString();
        if (!packageName.equals(activePackage)) {
            handler.removeCallbacks(finishCheck);
            generating = false;
            awaitingCompletion = false;
            pendingResponse = "";
            activePackage = packageName;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        boolean nowGenerating = isGenerating(root);
        String response = findLatestResponse(root);

        if (nowGenerating && !generating) {
            announce("Generating", "generation-start");
            awaitingCompletion = true;
        }
        if (nowGenerating) {
            handler.removeCallbacks(finishCheck);
            pendingResponse = response;
        } else if (awaitingCompletion) {
            pendingResponse = response;
            handler.removeCallbacks(finishCheck);
            handler.postDelayed(finishCheck, SETTLE_MS);
        }
        generating = nowGenerating;
    }

    private boolean isGenerating(AccessibilityNodeInfo root) {
        if (CHATGPT_PACKAGE.equals(activePackage)) {
            return hasDescription(root, "Stop") || hasText(root, "Thinking");
        }
        if (CLAUDE_PACKAGE.equals(activePackage)) {
            return hasDescription(root, "Stop responding")
                    || hasDescription(root, "Claude is thinking")
                    || hasText(root, "Sending…");
        }
        return hasViewId(root, GEMINI_STOP_ID) || hasDescription(root, "Stop generating response");
    }

    private String findLatestResponse(AccessibilityNodeInfo root) {
        if (CHATGPT_PACKAGE.equals(activePackage)) return findLatestChatGptResponse(root);
        if (CLAUDE_PACKAGE.equals(activePackage)) return findLatestClaudeResponse(root);
        return findLatestGeminiResponse(root);
    }

    private String findLatestGeminiResponse(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> messages = root.findAccessibilityNodeInfosByViewId(GEMINI_MESSAGE_CONTENT_ID);
        if (messages == null || messages.isEmpty()) return "";

        AccessibilityNodeInfo message = messages.get(messages.size() - 1);
        ArrayList<String> parts = new ArrayList<>();
        appendGeminiContent(message, parts);
        return TextUtils.join("\n", parts).trim();
    }

    private static void appendGeminiContent(AccessibilityNodeInfo node, ArrayList<String> parts) {
        String id = node.getViewIdResourceName();
        if (GEMINI_CODE_BLOCK_ID.equals(id)) {
            List<AccessibilityNodeInfo> codeNodes = node.findAccessibilityNodeInfosByViewId(GEMINI_CODE_IMPL_ID);
            if (codeNodes != null && !codeNodes.isEmpty()) {
                String code = clean(codeNodes.get(0).getText());
                if (!code.isEmpty()) {
                    parts.add("Code block:");
                    parts.add(code);
                    parts.add("Code block end.");
                }
            }
            return;
        }
        if (GEMINI_RESPONSE_ID.equals(id)) {
            String prose = clean(node.getText());
            if (!prose.isEmpty()) parts.add(prose);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) appendGeminiContent(child, parts);
        }
    }

    private String findLatestChatGptResponse(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo readAloud = findLastByDescription(root, "Read aloud");
        if (readAloud == null) return "";

        AccessibilityNodeInfo branch = readAloud;
        AccessibilityNodeInfo message = branch.getParent();
        while (message != null && collectTextNodes(message).isEmpty()) {
            branch = message;
            message = message.getParent();
        }
        if (message == null) return "";

        AccessibilityNodeInfo content = null;
        for (int i = 0; i < message.getChildCount(); i++) {
            AccessibilityNodeInfo child = message.getChild(i);
            if (child != null && child != branch && !collectTextNodes(child).isEmpty()) {
                content = child;
                break;
            }
        }
        if (content == null) return "";

        ArrayList<String> parts = new ArrayList<>();
        appendChatGptContent(content, content, parts);
        // ChatGPT writing cards expose a short card title before the actual answer.
        // It is metadata (often a repeated topic such as "Rain"), not response prose.
        if (parts.size() >= 2
                && !"Code block:".equals(parts.get(0))
                && parts.get(0).length() <= 80
                && parts.get(1).length() > parts.get(0).length() * 2) {
            Log.d(TAG, "Ignoring ChatGPT card title; characters=" + parts.get(0).length());
            parts.remove(0);
        }
        return TextUtils.join("\n", parts).trim();
    }

    private static void appendChatGptContent(AccessibilityNodeInfo node,
            AccessibilityNodeInfo boundary, ArrayList<String> parts) {
        if (isChatGptCodeBlock(node)) {
            ArrayList<String> codeParts = new ArrayList<>();
            for (AccessibilityNodeInfo textNode : collectTextNodes(node)) {
                String text = clean(textNode.getText());
                if (!text.isEmpty() && !codeParts.contains(text)) codeParts.add(text);
            }
            if (!codeParts.isEmpty()) {
                parts.add("Code block:");
                parts.add(TextUtils.join("\n", codeParts));
                parts.add("Code block end.");
            }
            return;
        }
        if (node.getChildCount() == 0) {
            if (isSuggestionInCluster(node, boundary)) return;
            String text = clean(node.getText());
            if (!text.isEmpty() && !parts.contains(text)) parts.add(text);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) appendChatGptContent(child, boundary, parts);
        }
    }

    private static boolean isChatGptCodeBlock(AccessibilityNodeInfo node) {
        if (!hasDescription(node, "Copy code") || collectTextNodes(node).isEmpty()) return false;
        // Select the smallest common container of the code text and Copy code control.
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && hasDescription(child, "Copy code")
                    && !collectTextNodes(child).isEmpty()) return false;
        }
        return true;
    }

    private String findLatestClaudeResponse(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo anchor = findLastByDescription(root, "Copy message");
        if (anchor == null) return "";

        AccessibilityNodeInfo message = anchor;
        while (message != null) {
            for (int actionIndex = message.getChildCount() - 1; actionIndex >= 1; actionIndex--) {
                AccessibilityNodeInfo actions = message.getChild(actionIndex);
                if (actions == null || !hasDescription(actions, "Copy message")) continue;

                int contentStart = 0;
                for (int i = 0; i < actionIndex; i++) {
                    AccessibilityNodeInfo child = message.getChild(i);
                    if (child != null && child.isLongClickable()
                            && !collectTextNodes(child).isEmpty()) contentStart = i + 1;
                }
                ArrayList<String> parts = new ArrayList<>();
                for (int i = contentStart; i < actionIndex; i++) {
                    AccessibilityNodeInfo child = message.getChild(i);
                    if (child != null) appendClaudeContent(child, parts);
                }
                if (!parts.isEmpty()) return TextUtils.join("\n", parts).trim();
            }
            message = message.getParent();
        }
        return "";
    }

    private static void appendClaudeContent(AccessibilityNodeInfo node, ArrayList<String> parts) {
        if (isClaudeCodeBlock(node)) {
            ArrayList<String> codeParts = new ArrayList<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null || hasDescription(child, "Copy code")) continue;
                for (AccessibilityNodeInfo textNode : collectTextNodes(child)) {
                    String text = clean(textNode.getText());
                    if (!text.isEmpty() && !codeParts.contains(text)) codeParts.add(text);
                }
            }
            if (!codeParts.isEmpty()) {
                parts.add("Code block:");
                parts.add(TextUtils.join("\n", codeParts));
                parts.add("Code block end.");
            }
            return;
        }
        if (node.getChildCount() == 0) {
            String text = clean(node.getText());
            if (!text.isEmpty() && !parts.contains(text)) parts.add(text);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) appendClaudeContent(child, parts);
        }
    }

    private static boolean isClaudeCodeBlock(AccessibilityNodeInfo node) {
        boolean hasHeader = false;
        boolean hasCode = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            if (hasDescription(child, "Copy code") && hasDescription(child, "Expand code")) {
                hasHeader = true;
            } else if (!collectTextNodes(child).isEmpty()) {
                hasCode = true;
            }
        }
        if (!hasHeader || !hasCode) return false;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && isClaudeCodeBlock(child)) return false;
        }
        return true;
    }

    private static boolean isSuggestionInCluster(AccessibilityNodeInfo textNode, AccessibilityNodeInfo boundary) {
        AccessibilityNodeInfo node = textNode.getParent();
        while (node != null && node != boundary) {
            if (node.isClickable()) {
                AccessibilityNodeInfo parent = node.getParent();
                return parent != null && collectTextNodes(parent).size() > 1;
            }
            node = node.getParent();
        }
        return false;
    }

    private static ArrayList<AccessibilityNodeInfo> collectTextNodes(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> result = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        for (int i = 0; i < queue.size(); i++) {
            AccessibilityNodeInfo current = queue.get(i);
            if (!clean(current.getText()).isEmpty() && current.getChildCount() == 0) result.add(current);
            for (int c = 0; c < current.getChildCount(); c++) {
                AccessibilityNodeInfo child = current.getChild(c);
                if (child != null) queue.add(child);
            }
        }
        return result;
    }

    private static boolean hasViewId(AccessibilityNodeInfo node, String id) {
        List<AccessibilityNodeInfo> matches = node.findAccessibilityNodeInfosByViewId(id);
        return matches != null && !matches.isEmpty();
    }

    private static boolean hasDescription(AccessibilityNodeInfo node, String wanted) {
        return findLastByDescription(node, wanted) != null;
    }

    private static AccessibilityNodeInfo findLastByDescription(AccessibilityNodeInfo node, String wanted) {
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(node);
        AccessibilityNodeInfo found = null;
        for (int i = 0; i < queue.size(); i++) {
            AccessibilityNodeInfo current = queue.get(i);
            CharSequence description = current.getContentDescription();
            if (description != null && wanted.contentEquals(description)) found = current;
            for (int c = 0; c < current.getChildCount(); c++) {
                AccessibilityNodeInfo child = current.getChild(c);
                if (child != null) queue.add(child);
            }
        }
        return found;
    }

    private static boolean hasText(AccessibilityNodeInfo node, String wanted) {
        for (AccessibilityNodeInfo textNode : collectTextNodes(node)) {
            if (wanted.equals(clean(textNode.getText()))) return true;
        }
        return false;
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().trim().replaceAll("[\\t ]+", " ");
    }

    private void announce(String text) { announce(text, "completed-response"); }

    private void announce(String text, String kind) {
        Log.i(TAG, "Announcing; kind=" + kind + ", package=" + activePackage + ", characters=" + text.length());
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setPackageName(getPackageName());
        event.setClassName(getClass().getName());
        event.getText().add(text);
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager != null && manager.isEnabled()) manager.sendAccessibilityEvent(event);
    }

    @Override public void onInterrupt() { handler.removeCallbacksAndMessages(null); }

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String component = new ComponentName(context, AiResponseService.class).flattenToString();
        for (String item : enabled.split(":")) if (component.equalsIgnoreCase(item)) return true;
        return false;
    }
}

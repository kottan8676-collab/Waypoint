# Waypoint

Waypoint is an Android accessibility utility. Its first feature watches Gemini, ChatGPT, and Claude accessibility hierarchies, announces “Generating” through TalkBack, and announces the completed response after streaming settles.

When a response contains code, Waypoint announces “Code block,” reads the code, announces “Code block end,” and then continues with the response. This works with multiple code blocks in one response.

## Observed Gemini accessibility contract

- Package: `com.google.android.googlequicksearchbox`
- Conversation list: `assistant_robin_chat_history_list`
- User prompt: `assistant_robin_user_message_text`
- AI response text: `assistant_robin_text`
- Generating signal: `assistant_robin_stop_icon_button_compose` and the description `Stop generating response`
- Completion corroboration: the stop control disappears and response actions such as `Listen`, `Copy`, and rating controls appear.

These IDs were observed on a Samsung Galaxy S25 running the Gemini surface inside the Google app on 2026-09-08. They are implementation details and may change in a future Gemini release.

## Observed ChatGPT accessibility contract

- Package: `com.openai.chatgpt`
- Composer: description `Reply to ChatGPT`
- Generating signals: text `Thinking` and control description `Stop`
- Completed-response anchor: the latest action row containing `Read aloud`, alongside `Copy`, rating, sharing, and more-actions controls
- Response extraction: text and code blocks in the content branch associated with that action row; clustered clickable follow-up suggestions are excluded

ChatGPT's Compose hierarchy did not expose stable resource IDs in the observed build, so Waypoint uses these semantic relationships rather than screen coordinates.

## Observed Claude accessibility contract

- Package: `com.anthropic.claude`
- Composer placeholder: `Chat with Claude…` or `Reply to Claude…`
- Generating signals: description `Claude is thinking`, control description `Stop responding`, and transitional text `Sending…`
- Completed-response anchor: the latest action row containing `Copy message`, alongside `Share`, `Narrate`, feedback, and retry controls
- Response extraction: all response content after the latest user prompt and before that action row, excluding the “Claude is AI and can make mistakes” disclaimer

Claude's observed Compose hierarchy did not expose response resource IDs, so Waypoint uses semantic descriptions and sibling relationships rather than coordinates.

## Build

Open the root directory in Android Studio, use JDK 17, and build the `app` module. The project targets Android 15 (API 35) and supports Android 8+.

After installation, open Waypoint and choose **Open accessibility settings**, then enable **Waypoint AI response reader**. TalkBack should remain enabled.

## Install

Download the APK from the latest GitHub release. Because Waypoint is not distributed through an app store, Android may ask you to allow installation from the app you used to open the APK.

## Privacy

The service requests window-content access because that is required to identify completed AI responses. It performs no networking and stores no response history.

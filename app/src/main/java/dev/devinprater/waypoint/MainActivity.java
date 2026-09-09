package dev.devinprater.waypoint;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 72, 48, 48);

        TextView title = new TextView(this);
        title.setText("Waypoint");
        title.setTextSize(32);
        title.setAccessibilityHeading(true);
        root.addView(title, matchWrap());

        TextView summary = new TextView(this);
        summary.setText("Waypoint announces when Gemini, ChatGPT, or Claude starts generating and reads the response after it finishes. TalkBack must be on, and the Waypoint accessibility service must be enabled.");
        summary.setTextSize(18);
        summary.setPadding(0, 32, 0, 32);
        root.addView(summary, matchWrap());

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, 0, 0, 24);
        root.addView(status, matchWrap());

        Button settings = new Button(this);
        settings.setText("Open accessibility settings");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(settings, matchWrap());

        TextView privacy = new TextView(this);
        privacy.setText("Processing stays on this device. Waypoint reads screen text only while its accessibility service is enabled.");
        privacy.setTextSize(15);
        privacy.setPadding(0, 32, 0, 0);
        root.addView(privacy, matchWrap());
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        status.setText(AiResponseService.isEnabled(this) ? "Status: enabled" : "Status: not enabled");
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}

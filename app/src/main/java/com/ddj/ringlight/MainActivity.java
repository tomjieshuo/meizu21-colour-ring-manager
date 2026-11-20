package com.ddj.ringlight;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class MainActivity extends Activity {
    private static final String NODE = "/sys/class/leds/aw20072_led/";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BufferedWriter rootIn;
    private Process rootProcess;
    private boolean rootReady = false;
    private boolean rainbow = false;
    private int brightness = 48;
    private float hue = 0f;
    private View preview;
    private TextView status, colorText, brightText;
    private Button rainbowButton;

    private final Runnable rainbowTick = new Runnable() {
        @Override public void run() {
            if (!rainbow || !rootReady) return;
            hue = (hue + 2.2f) % 360f;
            int c = Color.HSVToColor(new float[]{hue, 1f, 1f});
            preview.setBackgroundColor(c);
            colorText.setText(String.format("#%06X", (0xFFFFFF & c)));
            setColor(c);
            handler.postDelayed(this, 120);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        connectRoot();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(20));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(17,19,24));

        TextView title = text("灵动光环", 28, true);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        TextView subtitle = text("MEIZU 21 · RGB 环形灯", 14, false);
        subtitle.setTextColor(0xFF9AA4B2);
        root.addView(subtitle);

        preview = new View(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(112), dp(112));
        pp.setMargins(0, dp(26), 0, dp(12));
        preview.setBackgroundColor(Color.RED);
        root.addView(preview, pp);
        colorText = text("#FF0000", 18, true);
        colorText.setTextColor(Color.WHITE);
        root.addView(colorText);

        TextView cl = text("颜色", 15, true);
        cl.setTextColor(0xFFE5E7EB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(24);
        root.addView(cl, lp);

        SeekBar colors = new SeekBar(this);
        colors.setMax(359);
        colors.setProgress(0);
        root.addView(colors, new LinearLayout.LayoutParams(-1, -2));
        colors.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (u && !rainbow) {
                    hue = p;
                    int c = Color.HSVToColor(new float[]{hue, 1f, 1f});
                    preview.setBackgroundColor(c);
                    colorText.setText(String.format("#%06X", 0xFFFFFF & c));
                    setColor(c);
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        brightText = text("亮度：48 / 63", 15, true);
        brightText.setTextColor(0xFFE5E7EB);
        lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(18);
        root.addView(brightText, lp);

        SeekBar bright = new SeekBar(this);
        bright.setMax(63);
        bright.setProgress(brightness);
        root.addView(bright, new LinearLayout.LayoutParams(-1, -2));
        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                brightness = Math.max(1, p);
                brightText.setText("亮度：" + p + " / 63");
                if (u && !rainbow) setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        rainbowButton = button("开始 RGB 彩虹模式");
        lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(28);
        root.addView(rainbowButton, lp);
        rainbowButton.setOnClickListener(v -> {
            if (!rootReady) { connectRoot(); return; }
            rainbow = !rainbow;
            if (rainbow) {
                rainbowButton.setText("停止彩虹模式");
                handler.post(rainbowTick);
            } else {
                stopRainbow();
            }
        });

        Button off = button("关闭灯环");
        lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(12);
        root.addView(off, lp);
        off.setOnClickListener(v -> {
            stopRainbow();
            write("printf '0\\n' > " + NODE + "effect");
            status.setText("已尝试发送关闭指令（节点格式尚未验证）");
        });

        status = text("正在检查 Root 权限…", 13, false);
        status.setTextColor(0xFF9AA4B2);
        lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(24);
        root.addView(status, lp);
        setContentView(root);
    }

    private void connectRoot() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("su");
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                w.write("id; test -w " + NODE + "all_light && echo NODE_OK || echo NODE_NO\\n");
                w.flush();
                String a = r.readLine();
                String b = r.readLine();
                boolean ok = a != null && a.contains("uid=0") && "NODE_OK".equals(b);
                runOnUiThread(() -> {
                    rootProcess = p;
                    rootIn = w;
                    rootReady = ok;
                    status.setText(ok
                        ? "Root 已授权，候选灯环节点可写（协议未验证）"
                        : "未获得 Root 或未找到候选 aw20072 节点；请检查 KSU/Magisk 授权及固件");
                    status.setTextColor(ok ? 0xFF65D68A : 0xFFFF8A80);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Root 初始化失败：" + e.getMessage()));
            }
        }).start();
    }

    private void setColor(int color) {
        if (!rootReady) return;
        int rgb = color & 0xFFFFFF;
        write(String.format(java.util.Locale.US,
            "printf '%d %06x\\n' > %sall_light", brightness, rgb, NODE));
    }

    private synchronized void write(String cmd) {
        try {
            if (rootIn != null) { rootIn.write(cmd + "\\n"); rootIn.flush(); }
        } catch (Exception e) {
            rootReady = false;
            runOnUiThread(() -> status.setText("写入失败：Root shell 已断开"));
        }
    }

    private void stopRainbow() {
        rainbow = false;
        handler.removeCallbacks(rainbowTick);
        if (rainbowButton != null) rainbowButton.setText("开始 RGB 彩虹模式");
    }

    @Override protected void onDestroy() {
        stopRainbow();
        try {
            if (rootIn != null) { rootIn.write("exit\\n"); rootIn.flush(); rootIn.close(); }
            if (rootProcess != null) rootProcess.destroy();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private TextView text(String s, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }
    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15);
        return b;
    }
    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + 0.5f);
    }
}

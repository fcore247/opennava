package site.navatv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PhoneLinkActivity extends Activity {
    private static final int WHITE = 0xfff6f8ef, MUTED = 0xffb8cbbf, INK = 0xff19281f;
    private static final int WRAP = -2, MATCH = -1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> request;
    private int generation;
    private TextView hint, route, refresh, enterPort, placeholder;
    private ImageView code;
    private int qrSize;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout page = new LinearLayout(this);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(48), dp(20), dp(48), dp(20));
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff101b16, 0xff22392d, 0xff14251f}));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        page.addView(copy, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView eyebrow = text("OpenNava Companion", 16, 600, 0xffc2dfa9);
        copy.addView(eyebrow);
        TextView title = text("Connect your phone", 39, 650, WHITE);
        copy.addView(title, spaced(WRAP, WRAP, 9));
        hint = text("", 18, 400, WHITE);
        copy.addView(hint, spaced(MATCH, WRAP, 13));
        route = text("", 14, 450, MUTED);
        copy.addView(route, spaced(MATCH, WRAP, 16));
        LinearLayout actions = new LinearLayout(this);
        copy.addView(actions, spaced(MATCH, WRAP, 20));
        TextView back = button("Back", this::finish);
        actions.addView(back);
        refresh = button("Check again", () -> load(0));
        LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(WRAP, WRAP);
        next.leftMargin = dp(9);
        actions.addView(refresh, next);
        enterPort = button("Enter port", this::choosePort);
        copy.addView(enterPort, spaced(WRAP, WRAP, 8));
        enterPort.setVisibility(View.GONE);

        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(shape(0xffffffff, 16));
        int width = getResources().getConfiguration().screenWidthDp;
        int height = getResources().getConfiguration().screenHeightDp;
        qrSize = dp(Math.min(width * 38 / 100, height - 84));
        LinearLayout.LayoutParams tileSize = new LinearLayout.LayoutParams(qrSize, qrSize);
        tileSize.leftMargin = dp(30);
        page.addView(tile, tileSize);
        code = new ImageView(this);
        code.setScaleType(ImageView.ScaleType.CENTER);
        code.setContentDescription("QR code for OpenNava Companion");
        tile.addView(code, new FrameLayout.LayoutParams(MATCH, MATCH));
        placeholder = text("Checking connection…", 16, 550, INK);
        placeholder.setGravity(Gravity.CENTER);
        tile.addView(placeholder, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(page);
        back.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); load(0); }

    @Override protected void onStop() {
        generation++;
        if (request != null) request.cancel(true);
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void load(int port) {
        final int current = ++generation;
        if (request != null) request.cancel(true);
        code.setImageDrawable(null);
        placeholder.setText("Checking connection…");
        placeholder.setVisibility(View.VISIBLE);
        hint.setText("Looking up this TV’s connection address.");
        route.setText("");
        enterPort.setVisibility(View.GONE);
        request = worker.submit(() -> {
            try {
                PhoneLinkAddress address = PhoneLinkAddress.find(this, port);
                if (address.host() == null || address.port == 0) {
                    runOnUiThread(() -> {
                        if (generation != current) return;
                        showUnavailable(address.host() != null);
                    });
                    return;
                }
                Bitmap qr = barcode(address.invitation(this).toString(), qrSize);
                runOnUiThread(() -> {
                    if (generation != current) return;
                    code.setImageBitmap(qr);
                    placeholder.setVisibility(View.GONE);
                    hint.setText("Launch OpenNava Companion on your phone. Under Devices, choose “Scan QR code”.");
                    String transport = address.tail != null ? "Tailscale" : "Home network";
                    route.setText(transport + "   " + address.host() + " · Port " + address.port
                            + (address.tail != null && address.lan != null ? "\nHome network   " + address.lan : ""));
                });
            } catch (Exception failed) {
                runOnUiThread(() -> { if (generation == current) showUnavailable(false); });
            }
        });
    }

    private void showUnavailable(boolean hasAddress) {
        placeholder.setText("No connection code available");
        hint.setText(hasAddress
                ? "Enable ADB debugging on the TV. Under “Wireless debugging”, the connection port appears next to the IP address."
                : "Connect your TV to your home network or Tailscale, then check the connection again.");
        enterPort.setVisibility(hasAddress ? View.VISIBLE : View.GONE);
    }

    private void choosePort() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setHint("Connection port");
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connection port")
                .setView(field).setNegativeButton("Cancel", null).setPositiveButton("Check", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int number = Integer.parseInt(field.getText().toString().trim());
                if (number <= 0 || number > 65535) throw new NumberFormatException();
                dialog.dismiss();
                load(number);
            } catch (NumberFormatException invalid) { field.setError("Enter a port between 1 and 65535"); }
        });
    }

    private static Bitmap barcode(String value, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
                Collections.singletonMap(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        int[] row = new int[size];
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        image.setDensity(Bitmap.DENSITY_NONE);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) row[x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            image.setPixels(row, 0, size, 0, y, size, 1);
        }
        return image;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams spaced(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.topMargin = dp(top); return p;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }

    private TextView text(String value, int size, int weight, int color) {
        TextView label = new TextView(this);
        label.setText(value); label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        Typeface face = Typeface.create(weight >= 550 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
        label.setTypeface(android.os.Build.VERSION.SDK_INT >= 28 ? Typeface.create(face, weight, false) : face);
        label.setIncludeFontPadding(false);
        return label;
    }

    private TextView button(String label, Runnable run) {
        TextView b = text(label, 15, 600, WHITE);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(17), dp(10), dp(17), dp(10));
        b.setFocusable(true); b.setBackground(shape(0xff344d40, 24));
        b.setOnFocusChangeListener((v, focused) -> {
            b.setBackground(shape(focused ? 0xffd1e7bf : 0xff344d40, 24));
            b.setTextColor(focused ? INK : WHITE);
        });
        b.setOnClickListener(v -> run.run());
        return b;
    }
}

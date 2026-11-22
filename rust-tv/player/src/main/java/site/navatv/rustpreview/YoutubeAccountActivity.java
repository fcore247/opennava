package site.navatv.rustpreview;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

/** Google device pairing for the native TV catalogue. The phone handles account consent. */
public final class YoutubeAccountActivity extends Activity {
    private static final int TEXT = 0xfff6f5fa;
    private static final int SECONDARY = 0xffb9bac8;
    private static final int ACCENT = 0xffdfcefc;
    private static final int SURFACE = 0xff30303d;
    private LinearLayout info;
    private ImageView qr;
    private Disposable pairing;
    private int generation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout page = new LinearLayout(this);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(48), dp(20), dp(48), dp(20));
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff272331, 0xff17171e, 0xff101017}));
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        int side = Math.min(dp(210), Math.round(getResources().getDisplayMetrics().heightPixels * .47f));
        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        qr.setContentDescription("QR-Code zur Anmeldung bei YouTube");
        LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(side, side);
        art.leftMargin = dp(24);
        page.addView(qr, art);
        setContentView(page);
    }

    @Override protected void onStart() {
        super.onStart();
        if (YoutubeSession.service().isSigned()) connected();
        else beginPairing();
    }

    @Override protected void onStop() {
        cancelPairing();
        super.onStop();
    }

    private void cancelPairing() {
        generation++;
        if (pairing != null) pairing.dispose(); // interrupts the SDK's device-token polling worker
        pairing = null;
    }

    private void beginPairing() {
        cancelPairing();
        final int request = generation;
        page("Mit Google anmelden");
        line("Dein Anmeldecode wird geladen …", 18, SECONDARY, 12, false);
        button("Abbrechen", this::finish, true);
        pairing = YoutubeSession.service().signInObserve()
                .timeout(10, TimeUnit.MINUTES).observeOn(AndroidSchedulers.mainThread())
                .subscribe(code -> {
                    if (request != generation) return;
                    if (code == null || !code.matches("[A-Za-z0-9 -]{4,20}")) {
                        retry();
                        return;
                    }
                    pending(code);
                }, error -> {
                    if (request == generation) retry();
                }, () -> {
                    if (request != generation) return;
                    if (YoutubeSession.service().isSigned()) {
                        YoutubeSession.changed();
                        setResult(RESULT_OK);
                        finish();
                    }
                    else retry();
                });
    }

    private void pending(String code) {
        page("Mit Google anmelden");
        line("Scanne den QR-Code mit deinem Handy und bestätige die Verbindung bei Google.", 17, TEXT, 10, false);
        line("Oder öffne yt.be/activate und gib diesen Code ein.", 15, SECONDARY, 12, false);
        TextView digits = line(code, 28, ACCENT, 4, true);
        digits.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        // This is the first-party mobile link used by the bundled TV service. Only the
        // short, user-visible activation code goes in the QR, never the private device token.
        String link = new Uri.Builder().scheme("https").authority("youtube.com")
                .appendPath("qr").appendPath("activate").appendPath(code.replace(' ', '-')).build().toString();
        try {
            int side = qr.getLayoutParams().width;
            BitMatrix dots = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, side, side,
                    Collections.singletonMap(EncodeHintType.MARGIN, 4));
            int[] pixels = new int[side * side];
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) pixels[y * side + x] = dots.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            qr.setImageBitmap(Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888));
            qr.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
            // Keep the same usable Google code and manual URL if QR encoding fails.
        }
        button("Abbrechen", this::finish, true);
    }

    private void retry() {
        cancelPairing();
        page("Anmeldung erneut starten");
        line("Die Verbindung zu Google ist abgelaufen oder unterbrochen.", 17, SECONDARY, 10, false);
        button("Neuen Code anfordern", this::beginPairing, true);
        button("Zurück zu YouTube", this::finish, false);
    }

    private void connected() {
        page("Mit YouTube verbunden");
        Account account = YoutubeSession.service().getSelectedAccount();
        if (account != null && account.getName() != null && !account.getName().isEmpty()) {
            line(account.getName(), 20, SECONDARY, 10, false);
        }
        button("Zu meinen Videos", this::finish, true);
        button("Auf diesem TV abmelden", () -> {
            YoutubeSession.signOut();
            setResult(RESULT_OK);
            finish();
        }, false);
    }

    private void page(String title) {
        info.removeAllViews();
        qr.setImageDrawable(null);
        qr.setVisibility(View.GONE);
        line("YouTube", 17, ACCENT, 0, true);
        line(title, 32, TEXT, 8, true);
    }

    private TextView line(String text, int size, int color, int top, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        info.addView(view, p);
        return view;
    }

    private void button(String label, Runnable action, boolean focus) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(22));
        int[][] states = {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}};
        shape.setColor(new ColorStateList(states, new int[]{ACCENT, ACCENT, SURFACE}));
        button.setTextColor(new ColorStateList(states, new int[]{SURFACE, SURFACE, TEXT}));
        button.setBackground(shape);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(43));
        p.topMargin = dp(focus ? 18 : 6);
        info.addView(button, p);
        if (focus) button.requestFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

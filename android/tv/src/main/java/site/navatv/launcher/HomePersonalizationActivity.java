package site.navatv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** Download handoff for the companion; the QR contains only the public HTTPS page. */
public final class HomePersonalizationActivity extends Activity {
    private static final String DOWNLOAD = "https://opennava.site/handy/";
    private static final int PEARL = 0xfff3f1fa;
    private static final int LAVENDER = 0xffdbcced;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout page = new LinearLayout(this);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(54), dp(18), dp(54), dp(18));
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff272432, 0xff141923, 0xff11151d}));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        copy.addView(text("Personalisierung", 33, PEARL));
        TextView details = text("Scanne den Code mit deinem Android-Handy. In der OpenNava-App kannst du Apps und Ordner auf deinem TV anordnen.", 17, 0xffcdcfdd);
        details.setMaxWidth(dp(490));
        details.setPadding(0, dp(14), 0, dp(14));
        copy.addView(details);
        copy.addView(text("opennava.site/handy", 16, LAVENDER));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(18), 0, 0);
        copy.addView(actions);
        action(actions, "Handy verbinden", () -> startActivity(new Intent(this, PhoneLinkActivity.class)));
        action(actions, "Zurück", this::finish);
        int side = Math.min(dp(235), Math.round(getResources().getDisplayMetrics().heightPixels * .52f));
        ImageView qr = new ImageView(this);
        qr.setContentDescription("QR-Code zum Download der OpenNava-App");
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(DOWNLOAD, BarcodeFormat.QR_CODE, side, side);
            int[] pixels = new int[side * side];
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    pixels[y * side + x] = matrix.get(x, y) ? 0xff171721 : Color.WHITE;
                }
            }
            qr.setImageBitmap(Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(side, side);
            size.leftMargin = dp(30);
            page.addView(qr, size);
        } catch (WriterException ignored) {
            // The printed download address remains usable.
        }
        setContentView(page);
        actions.getChildAt(0).requestFocus();
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private void action(LinearLayout parent, String label, Runnable run) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setOnClickListener(v -> run.run());
        button.setOnFocusChangeListener((v, selected) -> paintButton(button, selected));
        paintButton(button, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(44));
        params.rightMargin = dp(12);
        parent.addView(button, params);
    }

    private void paintButton(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? LAVENDER : 0xff333441);
        background.setCornerRadius(dp(22));
        button.setTextColor(selected ? 0xff221f2b : PEARL);
        button.setBackground(background);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}

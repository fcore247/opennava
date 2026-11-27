package site.navatv.rustpreview;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import site.navatv.launcher.PhoneLinkAddress;
import site.navatv.launcher.SetupState;

/**
 * The first start on the television: a short intro, the code that downloads the phone app,
 * the code that pairs it, and then a calm screen that follows what the phone is showing.
 */
public final class SetupActivity extends Activity {
    private static final String DOWNLOAD = BuildConfig.COMPANION_DOWNLOAD;
    private static final int WHITE = 0xfffbfaff, SOFT = 0xffb9bbd0, LILAC = 0xffcabaff, INK = 0xff17122b;
    private static final int INTRO = 0, GET_APP = 1, PAIR = 2, FOLLOW = 3;
    private static final String[] TOUR = {
            "Live-TV", "Mediatheken & NavaTube", "Deine Startseite", "Alle Sender",
            "Alle Mediatheken", "Die Fernbedienung", "Der Mini-Player", "Frag deinen KI-Agenten"};
    private static final String[] TOUR_ENGLISH = {
            "Live TV", "Libraries & NavaTube", "Your start page", "All channels",
            "All libraries", "Your remote", "The mini player", "Ask your AI agent"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root, stage;
    private Typeface sans, serif, script;
    private int step = -1;
    private boolean resumed, looking;
    private String shownPhone = "", shownInvite = "";

    // Parts of the pairing page that change while it is open.
    private ImageView pairCode;
    private TextView pairWaiting, pairHint, pairSettings;
    private LinearLayout pairHelp;
    // Parts of the follow page.
    private TextView followTitle, followDetail;
    private LinearLayout followDots;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (SetupState.done(SetupActivity.this)) { leave(); return; }
            String phone = SetupState.phone(SetupActivity.this);
            if (!phone.isEmpty() && step != FOLLOW) show(FOLLOW);
            else if (step == FOLLOW && !phone.equals(shownPhone)) follow(phone);
            main.postDelayed(this, 500);
        }
    };
    private final Runnable lookAgain = this::findAddress;
    private final Runnable leaveIntro = () -> { if (step == INTRO) show(GET_APP); };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        // Opened again from the settings: begin a fresh run.
        if (state == null && SetupState.done(this)) SetupState.restart(this);
        sans = font("assets/fonts/Manrope.ttf", Typeface.SANS_SERIF);
        serif = font("assets/fonts/InstrumentSerif-Regular.ttf", Typeface.SERIF);
        script = font("assets/fonts/ReenieBeanie.ttf", Typeface.SERIF);
        root = new FrameLayout(this);
        root.addView(new Aurora(this), new FrameLayout.LayoutParams(-1, -1));
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        show(state == null ? INTRO : state.getInt("step", INTRO));
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        main.post(poll);
        if (step == PAIR) findAddress();
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(lookAgain);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (step == INTRO && event.getAction() == KeyEvent.ACTION_UP
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            show(GET_APP);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (step == PAIR) show(GET_APP);
        else if (step == GET_APP) show(INTRO);
        // The setup is left only through its own buttons, so a stray Back cannot lose it.
    }

    private boolean english;

    private String say(String german, String englishText) { return english ? englishText : german; }

    private void show(int next) {
        if (next == step) return;
        boolean forward = next > step;
        step = next;
        main.removeCallbacks(leaveIntro);
        main.removeCallbacks(lookAgain);
        // The phone may have chosen the language since the last page was built.
        english = site.navatv.launcher.RegionStore.english(this);
        View old = stage.getChildCount() > 0 ? stage.getChildAt(stage.getChildCount() - 1) : null;
        View page = next == INTRO ? intro() : next == GET_APP ? getApp() : next == PAIR ? pair() : followPage();
        stage.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.setAlpha(0);
        page.setTranslationX(old == null ? 0 : dp(forward ? 70 : -70));
        page.animate().alpha(1).translationX(0).setStartDelay(old == null ? 0 : 140).setDuration(520)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        if (old != null) {
            old.animate().cancel();
            old.animate().alpha(0).translationX(dp(forward ? -70 : 70)).setStartDelay(0).setDuration(260)
                    .withEndAction(() -> stage.removeView(old)).start();
        }
        if (next == PAIR) { shownInvite = ""; findAddress(); }
        if (next == FOLLOW) follow(SetupState.phone(this));
    }

    private void leave() {
        if (isFinishing()) return;
        SetupState.finish(this);
        HomeEntryActivity.openHome(this);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ---- pages --------------------------------------------------------------------------------

    private View intro() {
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER);
        TextView logo = text("Opennava", 128, WHITE, 400);
        logo.setTypeface(script);
        logo.setPadding(dp(44), 0, dp(56), 0);
        page.addView(logo, wrap(0));
        // The handwriting appears from left to right.
        Rect clip = new Rect(0, 0, 0, 2000);
        logo.setClipBounds(clip);
        logo.post(() -> {
            ValueAnimator write = ValueAnimator.ofInt(0, logo.getWidth());
            write.setStartDelay(350);
            write.setDuration(1500);
            write.addUpdateListener(a -> {
                clip.set(0, 0, (Integer) a.getAnimatedValue(), logo.getHeight());
                logo.setClipBounds(clip);
            });
            write.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator done) { logo.setClipBounds(null); }
            });
            write.start();
        });
        View line = new View(this);
        line.setBackground(shape(LILAC, 2));
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(dp(150), dp(3));
        under.topMargin = dp(2);
        page.addView(line, under);
        line.setScaleX(0);
        line.animate().scaleX(1).setStartDelay(1700).setDuration(600).start();
        TextView claim = title(say("Dein Fernseher. Ganz neu.", "Your TV. Brand new."), 34, WHITE);
        page.addView(claim, wrap(24));
        rise(claim, 2000);
        TextView hint = text(say("In wenigen Schritten eingerichtet", "Set up in a few steps"), 15, SOFT, 560);
        page.addView(hint, wrap(10));
        rise(hint, 2350);
        main.postDelayed(leaveIntro, 5200);
        return page;
    }

    private View getApp() {
        LinearLayout page = split();
        LinearLayout copy = copy(page, 1, say("Lade die App\nauf dein Handy", "Get the app\non your phone"),
                say("Scanne den Code mit der Kamera deines Handys und installiere OpenNava Companion.\n"
                        + "Ist die App installiert, drücke auf Weiter.", "Scan the code with your phone’s camera and install OpenNava Companion.\n"
                        + "Once the app is installed, press Next."));
        LinearLayout buttons = buttonRow();
        TextView next = button(say("Weiter  →", "Next  →"), true, () -> show(PAIR));
        buttons.addView(next);
        TextView skip = button(say("Überspringen", "Skip"), false, this::leave);
        LinearLayout.LayoutParams gap = wrap(0);
        gap.leftMargin = dp(12);
        buttons.addView(skip, gap);
        copy.addView(buttons, wrap(30));
        ImageView code = codeCard(page, say("App herunterladen", "Download the app"));
        draw(code, DOWNLOAD);
        next.post(next::requestFocus);
        return page;
    }

    private View pair() {
        LinearLayout page = split();
        LinearLayout copy = copy(page, 2, say("Öffne die App\nund scanne", "Open the app\nand scan"),
                say("Starte OpenNava Companion auf deinem Handy und scanne diesen zweiten Code. Handy und Fernseher müssen im selben WLAN sein.", "Open OpenNava Companion on your phone and scan this second code. Phone and TV need to be on the same Wi-Fi."));
        pairHint = text("", 14.5f, LILAC, 700);
        copy.addView(pairHint, fill(14));
        pairHelp = column();
        pairHelp.setBackground(shape(0x24ffffff, 18));
        pairHelp.setPadding(dp(18), dp(14), dp(18), dp(14));
        pairHelp.addView(text(say("Einmalig: Debugging einschalten", "Only once: turn on debugging"), 15, WHITE, 800));
        TextView how = text(say("Einstellungen → System → Info: siebenmal auf „Android TV OS-Build“.\n"
                + "Dann System → Entwickleroptionen → „Debugging über WLAN“ einschalten.", "Settings → System → About: press “Android TV OS build” seven times.\n"
                + "Then System → Developer options → turn on “Wireless debugging”."), 13, SOFT, 540);
        pairHelp.addView(how, fill(7));
        copy.addView(pairHelp, fill(10));
        pairHelp.setVisibility(View.GONE);
        LinearLayout buttons = buttonRow();
        TextView back = button(say("←  Zurück", "←  Back"), false, () -> show(GET_APP));
        buttons.addView(back);
        pairSettings = button(say("Entwickleroptionen", "Developer options"), false, this::openDeveloperSettings);
        LinearLayout.LayoutParams gap = wrap(0);
        gap.leftMargin = dp(12);
        buttons.addView(pairSettings, gap);
        TextView skip = button(say("Später", "Later"), false, this::leave);
        LinearLayout.LayoutParams last = wrap(0);
        last.leftMargin = dp(12);
        buttons.addView(skip, last);
        copy.addView(buttons, wrap(20));
        pairCode = codeCard(page, say("Mit der App scannen", "Scan with the app"));
        pairWaiting = text(say("Verbindung wird gesucht …", "Looking for the connection …"), 15, INK, 700);
        pairWaiting.setGravity(Gravity.CENTER);
        pairWaiting.setPadding(dp(20), 0, dp(20), 0);
        ((FrameLayout) pairCode.getParent()).addView(pairWaiting, new FrameLayout.LayoutParams(-1, -1));
        pairSettings.post(pairSettings::requestFocus);
        return page;
    }

    private void findAddress() {
        if (step != PAIR || looking || !resumed) return;
        looking = true;
        worker.execute(() -> {
            String invite = "";
            boolean network = false;
            try {
                PhoneLinkAddress address = PhoneLinkAddress.find(this, 0);
                network = address.hasHost();
                if (network && address.port() > 0) invite = address.invitation(this).toString();
            } catch (Exception unavailable) { /* shown as "not ready" and tried again */ }
            String found = invite;
            boolean online = network;
            main.post(() -> {
                looking = false;
                if (step != PAIR || isFinishing()) return;
                if (!found.isEmpty()) {
                    if (!found.equals(shownInvite)) {
                        shownInvite = found;
                        draw(pairCode, found);
                    }
                    pairWaiting.setVisibility(View.GONE);
                    pairHelp.setVisibility(View.GONE);
                    pairHint.setText(say("Fragt die App nach einem Code? Öffne „Entwickleroptionen“ → „Debugging über WLAN“ → „Gerät über einen Kopplungscode koppeln“.", "Does the app ask for a code? Open “Developer options” → “Wireless debugging” → “Pair device with pairing code”."));
                } else {
                    shownInvite = "";
                    pairCode.setImageDrawable(null);
                    pairWaiting.setVisibility(View.VISIBLE);
                    pairWaiting.setText(online ? say("Bitte Debugging einschalten", "Please turn on debugging") : say("Kein Netzwerk gefunden", "No network found"));
                    pairHint.setText(online ? say("Der Code erscheint hier von selbst.", "The code appears here by itself.") : say("Verbinde den Fernseher mit deinem WLAN.", "Connect your TV to your Wi-Fi."));
                    pairHelp.setVisibility(online ? View.VISIBLE : View.GONE);
                }
                main.postDelayed(lookAgain, found.isEmpty() ? 3000 : 12000);
            });
        });
    }

    private void openDeveloperSettings() {
        for (String action : new String[] {Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (RuntimeException unavailable) { /* try the main settings instead */ }
        }
    }

    private View followPage() {
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER);
        Check check = new Check(this);
        page.addView(check, new LinearLayout.LayoutParams(dp(112), dp(112)));
        followTitle = title(say("Verbunden", "Connected"), 58, WHITE);
        followTitle.setGravity(Gravity.CENTER);
        page.addView(followTitle, wrap(24));
        followDetail = text("", 18, SOFT, 560);
        followDetail.setGravity(Gravity.CENTER);
        page.addView(followDetail, wrap(12));
        followDots = new LinearLayout(this);
        followDots.setGravity(Gravity.CENTER);
        page.addView(followDots, wrap(28));
        TextView finish = button(say("Einrichtung beenden", "End setup"), false, this::leave);
        page.addView(finish, wrap(40));
        finish.post(finish::requestFocus);
        rise(followTitle, 350);
        rise(followDetail, 480);
        return page;
    }

    private void follow(String phone) {
        shownPhone = phone;
        followDots.removeAllViews();
        if (!phone.startsWith("tour-")) {
            followTitle.setText(say("Verbunden", "Connected"));
            followDetail.setText(say("Schau auf dein Handy – es führt dich jetzt durch die App.",
                    "Look at your phone – it will now show you around the app."));
            return;
        }
        int number = Math.max(1, Math.min(8, phone.charAt(5) - '0'));
        followTitle.setText((english ? TOUR_ENGLISH : TOUR)[number - 1]);
        followDetail.setText((number == 8 ? say("Er kann deine Mediathek einrichten.\n", "It can set up your library.\n") : "")
                + say("Dein Handy zeigt dir gerade Schritt " + number + " von 8.",
                        "Your phone is showing you step " + number + " of 8."));
        followTitle.setAlpha(0);
        followTitle.setTranslationY(dp(14));
        followTitle.animate().alpha(1).translationY(0).setStartDelay(0).setDuration(320).start();
        for (int i = 1; i <= 8; i++) {
            View dot = new View(this);
            dot.setBackground(shape(i <= number ? LILAC : 0x40ffffff, 4));
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(dp(i == number ? 30 : 8), dp(8));
            place.leftMargin = dp(7);
            followDots.addView(dot, place);
        }
    }

    // ---- building blocks ----------------------------------------------------------------------

    private LinearLayout split() {
        LinearLayout page = new LinearLayout(this);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(68), dp(30), dp(68), dp(30));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        return page;
    }

    private LinearLayout copy(LinearLayout page, int number, String heading, String body) {
        LinearLayout copy = column();
        page.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout steps = new LinearLayout(this);
        steps.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 1; i <= 2; i++) {
            View bar = new View(this);
            bar.setBackground(shape(i <= number ? LILAC : 0x40ffffff, 3));
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(dp(34), dp(5));
            place.rightMargin = dp(7);
            steps.addView(bar, place);
        }
        TextView label = text(say("SCHRITT ", "STEP ") + number + say(" VON 2", " OF 2"), 12, LILAC, 800);
        label.setLetterSpacing(.12f);
        label.setPadding(dp(8), 0, 0, 0);
        steps.addView(label);
        copy.addView(steps, wrap(0));
        TextView title = title(heading, 52, WHITE);
        title.setLineSpacing(0, .94f);
        copy.addView(title, fill(18));
        TextView words = text(body, 16.5f, SOFT, 540);
        words.setLineSpacing(dp(4), 1);
        copy.addView(words, fill(16));
        return copy;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    private ImageView codeCard(LinearLayout page, String caption) {
        LinearLayout side = column();
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(shape(Color.WHITE, 30));
        card.setElevation(dp(18));
        int size = dp(290);
        side.addView(card, new LinearLayout.LayoutParams(size, size));
        ImageView code = new ImageView(this);
        code.setScaleType(ImageView.ScaleType.FIT_CENTER);
        code.setPadding(dp(14), dp(14), dp(14), dp(14));
        code.setContentDescription("QR-Code: " + caption);
        card.addView(code, new FrameLayout.LayoutParams(-1, -1));
        TextView label = text(caption, 15, WHITE, 750);
        side.addView(label, wrap(18));
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-2, -2);
        place.leftMargin = dp(60);
        page.addView(side, place);
        card.setScaleX(.86f);
        card.setScaleY(.86f);
        card.animate().scaleX(1).scaleY(1).setStartDelay(260).setDuration(560)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
        return code;
    }

    private void draw(ImageView view, String value) {
        try {
            int size = 720;
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? INK : Color.WHITE;
            Bitmap image = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
            view.setImageBitmap(image);
        } catch (com.google.zxing.WriterException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private TextView button(String label, boolean primary, Runnable run) {
        TextView button = text(label, 15.5f, WHITE, 760);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine();
        button.setPadding(dp(21), dp(14), dp(21), dp(14));
        button.setFocusable(true);
        button.setBackground(shape(primary ? 0x40cabaff : 0x1fffffff, 28));
        button.setOnFocusChangeListener((v, focused) -> {
            button.setBackground(shape(focused ? LILAC : primary ? 0x40cabaff : 0x1fffffff, 28));
            button.setTextColor(focused ? INK : WHITE);
            button.animate().scaleX(focused ? 1.07f : 1).scaleY(focused ? 1.07f : 1).setStartDelay(0).setDuration(170).start();
        });
        button.setOnClickListener(v -> run.run());
        return button;
    }

    private void rise(View view, long delay) {
        view.setAlpha(0);
        view.setTranslationY(dp(22));
        view.animate().alpha(1).translationY(0).setStartDelay(delay).setDuration(620)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        return column;
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams fill(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private Typeface font(String asset, Typeface fallback) {
        try { return Typeface.createFromAsset(getAssets(), asset); }
        catch (RuntimeException absent) { return fallback; }
    }

    private TextView text(String value, float size, int color, int weight) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTypeface(sans);
        view.setFontVariationSettings("'wght' " + weight);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView title(String value, float size, int color) {
        TextView view = text(value, size, color, 400);
        view.setFontVariationSettings(null);
        view.setTypeface(serif);
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Three soft lights drifting over a night-blue ground. */
    private static final class Aurora extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colours = {0x667a5cff, 0x4d2fb6c9, 0x40ff7aa8};
        private final RadialGradient[] lights = new RadialGradient[3];
        private final android.graphics.Matrix move = new android.graphics.Matrix();

        Aurora(Context context) {
            super(context);
            for (int i = 0; i < 3; i++)
                lights[i] = new RadialGradient(0, 0, 1, colours[i], colours[i] & 0x00ffffff, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xff0c0b18);
            float w = getWidth(), h = getHeight();
            double t = SystemClock.uptimeMillis() / 1000.0;
            float[][] places = {
                    {.18f + .08f * (float) Math.sin(t * .23), .2f + .12f * (float) Math.cos(t * .19), .55f},
                    {.82f + .07f * (float) Math.cos(t * .17), .78f + .1f * (float) Math.sin(t * .21), .5f},
                    {.68f + .12f * (float) Math.sin(t * .13), .08f + .1f * (float) Math.sin(t * .27), .38f}};
            for (int i = 0; i < 3; i++) {
                float radius = places[i][2] * w, x = places[i][0] * w, y = places[i][1] * h;
                move.setScale(radius, radius);
                move.postTranslate(x, y);
                lights[i].setLocalMatrix(move);
                paint.setShader(lights[i]);
                canvas.drawCircle(x, y, radius, paint);
            }
            // The slow drift needs no more than about twenty frames a second on a small TV stick.
            if (ValueAnimator.areAnimatorsEnabled()) postInvalidateDelayed(50);
        }
    }

    /** A circle that closes, followed by its check mark. */
    private final class Check extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shown;

        Check(Context context) {
            super(context);
            ValueAnimator draw = ValueAnimator.ofFloat(0, 1);
            draw.setStartDelay(260);
            draw.setDuration(900);
            draw.addUpdateListener(a -> {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            });
            draw.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            float s = getWidth() / 100f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33cabaff);
            canvas.drawCircle(50 * s, 50 * s, 46 * s * Math.min(1, shown * 1.8f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5 * s);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(LILAC);
            canvas.drawArc(4 * s, 4 * s, 96 * s, 96 * s, -90, 360 * Math.min(1, shown * 1.5f), false, paint);
            float mark = Math.max(0, (shown - .45f) / .55f);
            if (mark <= 0) return;
            Path path = new Path();
            path.moveTo(29 * s, 52 * s);
            float first = Math.min(1, mark / .4f);
            path.lineTo((29 + 15 * first) * s, (52 + 14 * first) * s);
            if (mark > .4f) {
                float second = (mark - .4f) / .6f;
                path.lineTo((44 + 28 * second) * s, (66 - 29 * second) * s);
            }
            paint.setStrokeWidth(7 * s);
            paint.setColor(WHITE);
            canvas.drawPath(path, paint);
        }
    }
}

package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The first start of the phone app: scan the TV's code, connect, and hand over to the tour. */
final class SetupFlow {
    private static final int WELCOME = 0, SCAN = 1, CONNECTING = 2, CODE = 3, FAILED = 4, SUCCESS = 5, LANGUAGE = 6, COUNTRY = 7;

    private final MainActivity a;
    private final Surface u;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root, stage;
    private int step = WELCOME;
    private String picked = "", pickedCountry = "";
    private boolean active;
    private String raw = "";
    private TvInvite invite;
    private TvConnection attempt;
    private String notice = "", failure = "";
    /** Wireless debugging announces its pairing port on the network while the TV shows the code. */
    private int pairingPort;
    private NsdManager.DiscoveryListener pairingSearch;
    private TextView pairingState;
    private View portRow;

    SetupFlow(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    private android.content.SharedPreferences prefs() { return a.getSharedPreferences("setup", Context.MODE_PRIVATE); }

    boolean done() { return prefs().getBoolean("done", false); }

    boolean active() { return active; }

    /** The scanner belongs to this page, so its result comes here and not to the connection page. */
    boolean waitingForCode() { return active && step != SUCCESS && step != CONNECTING; }

    void begin() {
        active = true;
        step = WELCOME;
        invite = null;
        raw = "";
        notice = "";
        root = null;
    }

    void restore(String saved) {
        begin();
        TvInvite before = TvInvite.parse(saved);
        if (before != null) {
            raw = saved;
            invite = before;
            step = SCAN;
        }
    }

    String saved() { return raw; }

    View view() {
        if (root == null) {
            root = new FrameLayout(a);
            root.addView(new Lights(a), new FrameLayout.LayoutParams(MATCH, MATCH));
            stage = new FrameLayout(a);
            root.addView(stage, new FrameLayout.LayoutParams(MATCH, MATCH));
            draw(false);
        }
        return root;
    }

    /** Leave without connecting, or after the tour has taken over. */
    private void end(boolean remember) {
        active = false;
        stopPairingSearch();
        cancelAttempt();
        if (remember) prefs().edit().putBoolean("done", true).apply();
        root = null;
        a.page = "home";
        a.render();
        a.setupEnded();
    }

    boolean back() {
        if (step == WELCOME) {
            if (done()) { end(true); return true; }
            return false;
        }
        cancelAttempt();
        stopPairingSearch();
        go(step == LANGUAGE ? WELCOME : step == COUNTRY ? LANGUAGE : step == SCAN ? COUNTRY : SCAN);
        return true;
    }

    private void go(int next) {
        step = next;
        if (next != CODE) stopPairingSearch();
        if (root != null) draw(true);
    }

    private void draw(boolean animate) {
        View old = stage.getChildCount() > 0 ? stage.getChildAt(stage.getChildCount() - 1) : null;
        LinearLayout page = u.column();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        u.pad(page, 28, 20, 28, 24);
        pairingState = null;
        portRow = null;
        switch (step) {
            case WELCOME: welcome(page); break;
            case LANGUAGE: language(page); break;
            case COUNTRY: country(page); break;
            case SCAN: scan(page); break;
            case CONNECTING: connecting(page); break;
            case CODE: code(page); break;
            case FAILED: failed(page); break;
            default: success(page);
        }
        ScrollView holder = new ScrollView(a);
        holder.setFillViewport(true);
        holder.setVerticalScrollBarEnabled(false);
        holder.addView(page);
        stage.addView(holder, new FrameLayout.LayoutParams(MATCH, MATCH));
        if (old != null) {
            if (!animate) stage.removeView(old);
            else {
                old.animate().cancel();
                old.animate().alpha(0).translationX(-u.d(50)).setDuration(190).withEndAction(() -> stage.removeView(old)).start();
            }
        }
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            float alpha = child.getAlpha();
            child.setAlpha(0);
            child.setTranslationY(u.d(24));
            child.animate().alpha(alpha).translationY(0).setStartDelay(110 + i * 45L).setDuration(430)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    // ---- pages --------------------------------------------------------------------------------

    private void welcome(LinearLayout page) {
        flexible(page, 2);
        page.addView(centred(u.title("Welcome to", 34, 0xffd9d6ee)));
        u.space(page, 2);
        TextView logo = u.logo(96, WHITE);
        page.addView(logo, u.lp(WRAP, WRAP));
        writeOn(logo);
        View line = new View(a);
        line.setBackground(u.shape(LILAC, 2, 0));
        page.addView(line, u.lp(110, 3));
        line.setScaleX(0);
        line.animate().scaleX(1).setStartDelay(1750).setDuration(520).start();
        u.space(page, 28);
        page.addView(words("Your phone and your TV, finally together.\nSetting up takes about a minute."));
        flexible(page, 3);
        page.addView(primary("Get started", () -> go(LANGUAGE)), u.lp(MATCH, 58));
        u.space(page, 6);
        page.addView(quiet(done() ? "Close" : "I’ll do this later", () -> end(true)), u.lp(MATCH, 48));
    }

    /** Asked in both languages, because nothing is known about the viewer yet. */
    private void language(LinearLayout page) {
        top(page, 1);
        flexible(page, 1);
        page.addView(centred(u.title("Which language\ndo you speak?", 40, WHITE)));
        u.space(page, 8);
        page.addView(words("Welche Sprache sprichst du?"));
        u.space(page, 34);
        String now = Region.language(a);
        for (String[] choice : new String[][] {{"de", "Deutsch", "OpenNava auf Deutsch"}, {"en", "English", "OpenNava in English"}}) {
            page.addView(option(choice[1], choice[2], Region.chosen(a) && choice[0].equals(now), () -> {
                picked = choice[0];
                L.german = "de".equals(picked);
                go(COUNTRY);
            }), u.lp(MATCH, 82));
            u.space(page, 12);
        }
        flexible(page, 2);
    }

    private void country(LinearLayout page) {
        top(page, 1);
        u.space(page, 24);
        page.addView(centred(u.title("Where are you?", 40, WHITE)));
        u.space(page, 10);
        page.addView(words("Your country decides which TV channels and libraries OpenNava shows."));
        u.space(page, 26);
        String suggested = pickedCountry.isEmpty() ? Region.country(a) : pickedCountry;
        for (String code : new String[] {"de", "at", "ch", "us"}) {
            page.addView(option(Region.countryName(code), null, code.equals(suggested), () -> {
                pickedCountry = code;
                draw(false);
            }), u.lp(MATCH, 62));
            u.space(page, 10);
        }
        flexible(page, 1);
        page.addView(primary("Continue", () -> {
            pickedCountry = suggested;
            Region.save(a, picked.isEmpty() ? Region.language(a) : picked, suggested);
            a.regionChanged();
            go(SCAN);
        }), u.lp(MATCH, 58));
    }

    private LinearLayout option(String name, String detail, boolean selected, Runnable run) {
        LinearLayout row = u.row();
        u.pad(row, 22, 0, 18, 0);
        row.setBackground(u.shape(selected ? 0x40cabaff : 0x1fffffff, 24, selected ? LILAC : 0x33ffffff));
        row.setForeground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33cabaff), null,
                u.shape(WHITE, 24, 0)));
        LinearLayout words = u.column();
        words.addView(u.text(name, 18, WHITE, 780));
        if (detail != null) {
            u.space(words, 6);
            words.addView(u.text(detail, 13, 0xffc4c6d8, 520));
        }
        row.addView(words, u.weight(WRAP));
        row.addView(new Art(a, selected ? "ok" : "right", selected ? LILAC : MUTED), u.lp(20, 20));
        row.setOnClickListener(v -> run.run());
        row.setFocusable(true);
        row.setContentDescription(name);
        return row;
    }

    private void scan(LinearLayout page) {
        top(page, 2);
        flexible(page, 1);
        page.addView(new Viewfinder(a), u.lp(190, 190));
        u.space(page, 30);
        page.addView(centred(u.title("Scan your TV", 42, WHITE)));
        u.space(page, 12);
        page.addView(words("On your TV, press “Next” (“Weiter”) until you see the second code (step 2 of 2). Then scan it with this app."));
        if (!notice.isEmpty()) {
            u.space(page, 16);
            TextView warning = u.text(notice, 13.5f, 0xffffd9c9, 700);
            warning.setGravity(Gravity.CENTER);
            warning.setBackground(u.shape(0xff3a2a24, 16, 0));
            u.pad(warning, 14, 12, 14, 12);
            page.addView(warning, u.lp(MATCH, WRAP));
        }
        flexible(page, 2);
        page.addView(primary("Scan TV code", () -> {
            notice = "";
            a.scanTv();
        }), u.lp(MATCH, 58));
        u.space(page, 6);
        page.addView(quiet("Use a photo of the code", a::chooseQrPhoto), u.lp(MATCH, 48));
    }

    /** What the camera or a link delivered while the setup is open. */
    void scanned(String text) {
        if (text == null || !active) return;
        TvInvite found = TvInvite.parse(text);
        if (found == null) {
            notice = text.contains("OpenNava-Companion.apk")
                    ? "That was the download code. On your TV, press “Next” and scan the second code."
                    : "That is not an OpenNava TV code. Scan the code from step 2 on your TV.";
            go(SCAN);
            return;
        }
        notice = "";
        raw = text;
        invite = found;
        connect();
    }

    private void connect() {
        cancelAttempt();
        a.devices.cancelReconnect();
        go(CONNECTING);
        TvConnection candidate = new TvConnection(a);
        attempt = candidate;
        TvInvite target = invite;
        a.connecting = true;
        a.browse.execute(() -> {
            TvConnection.Device device = null;
            Exception problem = null;
            for (String host : new String[] {target.host, target.lan}) {
                if (host == null || attempt != candidate) continue;
                try {
                    device = candidate.connect(host, target.port);
                    break;
                } catch (Exception unavailable) { problem = unavailable; }
            }
            TvConnection.Device found = device;
            Exception reason = problem;
            a.runOnUiThread(() -> {
                if (attempt != candidate || !active) {
                    a.commands.execute(candidate::disconnect);
                    return;
                }
                attempt = null;
                a.connecting = false;
                if (found == null) {
                    failure = reason == null ? "" : a.error(reason);
                    // A changing port means wireless debugging, which first wants a pairing code.
                    go(target.port != 5555 ? CODE : FAILED);
                    return;
                }
                a.devices.adopt(candidate, found, raw);
                go(SUCCESS);
                // The television follows along and comes back from its settings to the setup screen.
                String region = Region.value(a);
                a.runTv(() -> {
                    candidate.setLocale(region);
                    candidate.setupStep("connected");
                    candidate.openHome();
                    return null;
                }, ignored -> {}, ignored -> {});
            });
        });
    }

    private void cancelAttempt() {
        TvConnection running = attempt;
        if (running == null) return;
        attempt = null;
        a.connecting = false;
        a.commands.execute(running::disconnect);
    }

    private void connecting(LinearLayout page) {
        top(page, 3);
        flexible(page, 1);
        page.addView(new Rings(a), u.lp(200, 200));
        u.space(page, 26);
        page.addView(centred(u.title("Connecting…", 40, WHITE)));
        u.space(page, 12);
        page.addView(words(invite.name + "\n" + L.t("If your TV asks to allow debugging, choose “Allow” and tick “Always”.")));
        flexible(page, 2);
        page.addView(quiet("Cancel", () -> {
            cancelAttempt();
            go(SCAN);
        }), u.lp(MATCH, 48));
    }

    private void code(LinearLayout page) {
        top(page, 3);
        u.space(page, 18);
        page.addView(centred(u.title("One last step", 40, WHITE)));
        u.space(page, 10);
        page.addView(words("Google TV asks for a code the first time. On your TV open:\n"
                + "Developer options  ›  Wireless debugging  ›\nPair device with pairing code"));
        u.space(page, 22);
        EditText field = new EditText(a);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6)});
        field.setHint("······");
        field.setGravity(Gravity.CENTER);
        field.setTextColor(WHITE);
        field.setHintTextColor(0xff5d6276);
        field.setTextSize(34);
        field.setLetterSpacing(.4f);
        field.setTypeface(u.sans);
        field.setBackground(u.shape(0xb3181c27, 22, 0x66cabaff));
        field.setContentDescription("Six-digit pairing code");
        page.addView(field, u.lp(MATCH, 76));
        u.space(page, 12);
        pairingState = u.text("", 13, MUTED, 620);
        pairingState.setGravity(Gravity.CENTER);
        page.addView(pairingState, u.lp(MATCH, WRAP));
        u.space(page, 8);
        EditText port = a.field("Pairing port shown on the TV", InputType.TYPE_CLASS_NUMBER, "");
        port.setTextColor(WHITE);
        port.setHintTextColor(0xff7b8095);
        portRow = port;
        page.addView(port, u.lp(MATCH, 52));
        port.setVisibility(View.GONE);
        flexible(page, 1);
        LinearLayout connect = primary("Pair and connect", () -> {
            String digits = field.getText().toString();
            int number = pairingPort;
            if (number == 0) {
                try { number = Integer.parseInt(port.getText().toString().trim()); }
                catch (NumberFormatException missing) { number = 0; }
            }
            if (digits.length() != 6) { a.say("Enter all six digits."); return; }
            if (number < 1 || number > 65535) {
                port.setVisibility(View.VISIBLE);
                a.say("Enter the port shown below the code on your TV.");
                return;
            }
            pair(digits, number);
        });
        page.addView(connect, u.lp(MATCH, 58));
        u.space(page, 6);
        page.addView(quiet("My TV asked me to “Allow” instead", this::connect), u.lp(MATCH, 48));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 6 && pairingPort != 0) {
                    InputMethodManager keyboard = a.getSystemService(InputMethodManager.class);
                    keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
                }
            }
        });
        startPairingSearch();
    }

    private void pair(String digits, int port) {
        go(CONNECTING);
        TvConnection candidate = new TvConnection(a);
        attempt = candidate;
        TvInvite target = invite;
        a.connecting = true;
        a.browse.execute(() -> {
            Exception problem = null;
            try { candidate.pair(target.host, port, digits); }
            catch (Exception refused) { problem = refused; }
            Exception reason = problem;
            a.runOnUiThread(() -> {
                if (attempt != candidate || !active) return;
                attempt = null;
                a.connecting = false;
                if (reason == null) connect();
                else {
                    go(CODE);
                    a.say(a.error(reason));
                }
            });
        });
    }

    private void startPairingSearch() {
        stopPairingSearch();
        pairingPort = 0;
        pairingState.setText(L.t("Looking for the code screen on your TV…"));
        NsdManager nsd = a.getSystemService(NsdManager.class);
        if (nsd == null || invite == null) return;
        TvInvite target = invite;
        NsdManager.DiscoveryListener search = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int error) {}
            @Override public void onStopDiscoveryFailed(String type, int error) {}
            @Override public void onServiceLost(NsdServiceInfo info) {}
            @Override @SuppressWarnings("deprecation") public void onServiceFound(NsdServiceInfo info) {
                NsdManager.DiscoveryListener mine = this;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo failed, int error) {}
                    @Override public void onServiceResolved(NsdServiceInfo resolved) {
                        String host = resolved.getHost() == null ? "" : resolved.getHost().getHostAddress();
                        if (!host.equals(target.host) && !host.equals(target.lan)) return;
                        main.post(() -> {
                            if (pairingSearch != mine || pairingState == null) return;
                            pairingPort = resolved.getPort();
                            pairingState.setText(L.t("✓  Code screen found on your TV"));
                            pairingState.setTextColor(0xff8ce8b4);
                            if (portRow != null) portRow.setVisibility(View.GONE);
                        });
                    }
                });
            }
        };
        pairingSearch = search;
        try { nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, search); }
        catch (RuntimeException unsupported) { pairingSearch = null; }
        main.postDelayed(() -> {
            if (pairingSearch == search && pairingPort == 0 && portRow != null) {
                portRow.setVisibility(View.VISIBLE);
                if (pairingState != null) pairingState.setText(L.t("Not found yet. You can also type the port from the TV."));
            }
        }, 9000);
    }

    private void stopPairingSearch() {
        NsdManager.DiscoveryListener search = pairingSearch;
        pairingSearch = null;
        if (search == null) return;
        try { a.getSystemService(NsdManager.class).stopServiceDiscovery(search); }
        catch (RuntimeException alreadyStopped) { /* nothing left to stop */ }
    }

    private void failed(LinearLayout page) {
        top(page, 3);
        flexible(page, 1);
        page.addView(centred(u.title("Not quite yet", 42, WHITE)));
        u.space(page, 14);
        page.addView(words("Check that phone and TV use the same Wi-Fi. If your TV shows “Allow debugging?”, choose “Allow”, then try again."));
        if (!failure.isEmpty()) {
            u.space(page, 14);
            TextView detail = u.text(failure, 12, 0xff8b90a4, 520);
            detail.setGravity(Gravity.CENTER);
            page.addView(detail, u.lp(MATCH, WRAP));
        }
        flexible(page, 2);
        page.addView(primary("Try again", this::connect), u.lp(MATCH, 58));
        u.space(page, 6);
        page.addView(quiet("Enter a pairing code instead", () -> go(CODE)), u.lp(MATCH, 48));
        page.addView(quiet("Scan again", () -> go(SCAN)), u.lp(MATCH, 48));
    }

    private void success(LinearLayout page) {
        top(page, 4);
        flexible(page, 2);
        FrameLayout badge = new FrameLayout(a);
        badge.setBackground(u.shape(0x338ce8b4, 70, 0));
        badge.addView(new Art(a, "ok", 0xff8ce8b4), new FrameLayout.LayoutParams(u.d(68), u.d(68), Gravity.CENTER));
        page.addView(badge, u.lp(140, 140));
        badge.setScaleX(.2f);
        badge.setScaleY(.2f);
        badge.animate().scaleX(1).scaleY(1).setStartDelay(220).setDuration(620)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
        u.space(page, 30);
        page.addView(centred(u.title("Connected", 46, WHITE)));
        u.space(page, 12);
        String television = invite == null ? L.t("Your TV") : invite.name;
        page.addView(words(L.german ? television + " und dein Handy sind jetzt gekoppelt."
                : television + " and your phone are now paired."));
        flexible(page, 3);
        page.addView(primary("Show me around", () -> {
            end(true);
            a.startTour();
        }), u.lp(MATCH, 58));
        u.space(page, 6);
        page.addView(quiet("Skip the tour", () -> {
            end(true);
            a.finishTvSetup();
        }), u.lp(MATCH, 48));
    }

    // ---- building blocks ----------------------------------------------------------------------

    /** The logo appears from left to right, as if it were being written. */
    private void writeOn(TextView logo) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        android.graphics.Rect clip = new android.graphics.Rect(0, 0, 0, 4000);
        logo.setClipBounds(clip);
        logo.post(() -> {
            ValueAnimator write = ValueAnimator.ofInt(0, logo.getWidth());
            write.setStartDelay(420);
            write.setDuration(1400);
            write.addUpdateListener(animation -> {
                clip.set(0, 0, (Integer) animation.getAnimatedValue(), logo.getHeight());
                logo.setClipBounds(clip);
            });
            write.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator done) { logo.setClipBounds(null); }
            });
            write.start();
        });
    }

    private void top(LinearLayout page, int number) {
        LinearLayout row = u.row();
        FrameLayout back = new FrameLayout(a);
        back.addView(new Art(a, "left", INK), new FrameLayout.LayoutParams(u.d(20), u.d(20), Gravity.CENTER));
        u.action(back, 0x1fffffff, 22, 0, a::onBackPressed);
        back.setContentDescription("Back");
        row.addView(back, u.lp(44, 44));
        row.addView(new View(a), u.weight(1));
        for (int i = 1; i <= 4; i++) {
            View dot = new View(a);
            dot.setBackground(u.shape(i <= number ? LILAC : 0x40ffffff, 4, 0));
            LinearLayout.LayoutParams place = u.lp(i == number ? 26 : 8, 8);
            place.leftMargin = u.d(6);
            row.addView(dot, place);
        }
        row.addView(new View(a), u.weight(1));
        u.gap(row, 44);
        page.addView(row, u.lp(MATCH, WRAP));
        if (step == SUCCESS) back.setVisibility(View.INVISIBLE);
    }

    private void flexible(LinearLayout page, int share) {
        page.addView(new View(a), new LinearLayout.LayoutParams(1, 0, share));
    }

    private TextView centred(TextView view) {
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(0, .95f);
        return view;
    }

    private TextView words(String text) {
        TextView view = u.text(text, 15.5f, 0xffb7bbcd, 520);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(u.d(4), 1);
        return view;
    }

    private LinearLayout primary(String label, Runnable run) {
        LinearLayout button = u.row();
        button.setGravity(Gravity.CENTER);
        button.addView(u.text(label, 16.5f, ON_LILAC, 800));
        button.setBackground(u.shape(LILAC, 29, 0));
        button.setForeground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33000000), null,
                u.shape(WHITE, 29, 0)));
        button.setOnClickListener(v -> run.run());
        button.setFocusable(true);
        button.setContentDescription(label);
        button.setElevation(u.d(6));
        return button;
    }

    private TextView quiet(String label, Runnable run) {
        TextView button = u.text(label, 14.5f, 0xffc9c4e6, 700);
        button.setGravity(Gravity.CENTER);
        u.action(button, 0, 24, 0, run);
        return button;
    }

    /** Soft lights drifting behind the setup. */
    private static final class Lights extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RadialGradient[] lights = new RadialGradient[3];
        private final android.graphics.Matrix move = new android.graphics.Matrix();

        Lights(Context context) {
            super(context);
            int[] colours = {0x667a5cff, 0x402fb6c9, 0x38ff7aa8};
            for (int i = 0; i < 3; i++)
                lights[i] = new RadialGradient(0, 0, 1, colours[i], colours[i] & 0x00ffffff, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xff0c0b18);
            float w = getWidth(), h = getHeight();
            double t = SystemClock.uptimeMillis() / 1000.0;
            float[][] places = {
                    {.15f + .12f * (float) Math.sin(t * .31), .16f + .05f * (float) Math.cos(t * .23), .85f},
                    {.9f + .1f * (float) Math.cos(t * .21), .72f + .06f * (float) Math.sin(t * .29), .8f},
                    {.75f + .18f * (float) Math.sin(t * .17), .02f + .05f * (float) Math.sin(t * .37), .6f}};
            for (int i = 0; i < 3; i++) {
                float radius = places[i][2] * w, x = places[i][0] * w, y = places[i][1] * h;
                move.setScale(radius, radius);
                move.postTranslate(x, y);
                lights[i].setLocalMatrix(move);
                paint.setShader(lights[i]);
                canvas.drawCircle(x, y, radius, paint);
            }
            if (ValueAnimator.areAnimatorsEnabled()) postInvalidateDelayed(40);
        }
    }

    /** Four corners and a line sweeping between them. */
    private final class Viewfinder extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Viewfinder(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), edge = u.d(46), r = u.d(26), inset = u.d(4);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x1affffff);
            canvas.drawRoundRect(inset, inset, w - inset, w - inset, r, r, paint);
            paint.setColor(0x80ffffff);
            float cell = u.d(13);
            int[] dots = {0, 1, 2, 5, 7, 8, 10, 12, 13, 14, 17, 18, 20, 22, 23, 24};
            for (int d : dots) {
                float x = w / 2 - cell * 3.1f + (d % 5) * cell * 1.3f, y = w / 2 - cell * 3.1f + (d / 5) * cell * 1.3f;
                canvas.drawRoundRect(x, y, x + cell, y + cell, u.d(3), u.d(3), paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(u.d(5));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(LILAC);
            for (int corner = 0; corner < 4; corner++) {
                canvas.save();
                canvas.rotate(corner * 90, w / 2, w / 2);
                canvas.drawLine(inset, inset + r, inset, inset + edge, paint);
                canvas.drawArc(inset, inset, inset + 2 * r, inset + 2 * r, 180, 90, false, paint);
                canvas.drawLine(inset + r, inset, inset + edge, inset, paint);
                canvas.restore();
            }
            float sweep = (float) (.5 - .5 * Math.cos(SystemClock.uptimeMillis() / 620.0));
            float y = u.d(30) + sweep * (w - u.d(60));
            paint.setStrokeWidth(u.d(3));
            canvas.drawLine(u.d(22), y, w - u.d(22), y, paint);
            if (isShown() && ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation();
        }
    }

    /** Rings travelling outwards from the middle while the connection is made. */
    private final class Rings extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Rings(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            float middle = getWidth() / 2f;
            double time = SystemClock.uptimeMillis() / 1800.0;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(u.d(3));
            for (int i = 0; i < 3; i++) {
                float phase = (float) ((time + i / 3.0) % 1);
                paint.setColor(LILAC);
                paint.setAlpha((int) (210 * (1 - phase)));
                canvas.drawCircle(middle, middle, u.d(26) + phase * (middle - u.d(30)), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(LILAC);
            canvas.drawCircle(middle, middle, u.d(24), paint);
            if (isShown() && ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation();
        }
    }
}

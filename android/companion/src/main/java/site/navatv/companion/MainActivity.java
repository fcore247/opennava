package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import site.navatv.launcher.PhoneCatalog;

import java.util.concurrent.*;

public final class MainActivity extends ComponentActivity {
    Surface ui;
    LinearLayout root, body;
    ScrollView scroll;
    volatile TvConnection tv;
    TvConnection.Device device;
    PhoneCatalog catalogue;
    final ExecutorService commands = Executors.newSingleThreadExecutor();
    final ExecutorService browse = Executors.newFixedThreadPool(3);
    private CatalogPage catalogPage;
    PhoneLivePage livePage;
    private HomePage homePage;
    private PhoneRecordingsPage recordingsPage;
    private PhoneTvContextController tvContext;
    private View menuButton;
    private String playbackTitle = "", playbackProvider = "";
    DevicePage devices;
    String page = "home", provider = "", query = "";
    int generation;
    boolean connecting, updating, selectingQr;
    private TextView connectionStatus;
    private LinearLayout connectionChip;
    private View connectionDot;
    private long lastNeedConnection;
    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(
            new ScanContract(), result -> {
                selectingQr = false;
                if (result.getContents() != null) devices.reviewInvite(result.getContents());
            });
    private final ActivityResultLauncher<PickVisualMediaRequest> qrPhoto = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) readQrPhoto(uri);
                else selectingQr = false;
            });

    interface IO<T> {
        T get() throws Exception;
    }

    interface Done<T> {
        void accept(T data);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        site.navatv.updates.Updates.home(this);
        ui = new Surface(this);
        tv = new TvConnection(getApplicationContext());
        catalogue = new PhoneCatalog(getApplicationContext());
        catalogPage = new CatalogPage(this);
        livePage = new PhoneLivePage(this);
        homePage = new HomePage(this);
        recordingsPage = new PhoneRecordingsPage(this);
        devices = new DevicePage(this);
        tvContext = new PhoneTvContextController(this);
        if (state != null) {
            page = state.getString("page", "home");
            playbackTitle = state.getString("playbackTitle", "");
            playbackProvider = state.getString("playbackProvider", "");
            provider = state.getString("provider", "");
            query = state.getString("query", "");
            livePage.selectedId = state.getString("liveChannel", "daserste");
            selectingQr = state.getBoolean("selectingQr", false);
        }
        render();
        if (state != null && state.containsKey("invite")) devices.reviewInvite(state.getString("invite"));
        else {
            consumeInvite(getIntent());
            if (state != null && state.getBoolean("menuOpen") && devices.pendingInvite == null)
                homePage.show();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeInvite(intent);
    }

    private void consumeInvite(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        String data = intent.getDataString();
        homePage.dismiss();
        intent.setData(null);
        intent.setAction(Intent.ACTION_MAIN);
        devices.reviewInvite(data);
    }

    void scanTv() {
        if (connecting || updating) return;
        devices.cancelReconnect();
        selectingQr = true;
        scanner.launch(new ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setOrientationLocked(false).setBeepEnabled(false)
                .setPrompt("Point the camera at the connection code in the full launcher."));
    }

    void chooseQrPhoto() {
        if (connecting || updating) return;
        devices.cancelReconnect();
        selectingQr = true;
        qrPhoto.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    private void readQrPhoto(Uri uri) {
        browse.execute(() -> {
            String content = null;
            try {
                Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, source) -> {
                            int size = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                            decoder.setTargetSampleSize(Math.max(1, (size + 2047) / 2048));
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
                int width = bitmap.getWidth(), height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                bitmap.recycle();
                BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
                content = new QRCodeReader().decode(image,
                        java.util.Collections.singletonMap(DecodeHintType.TRY_HARDER, true)).getText();
            } catch (java.io.IOException | com.google.zxing.ReaderException | RuntimeException invalidImage) {
                // A photo without a legible QR code can be retried from the device page.
            }
            final String result = content;
            runOnUiThread(() -> {
                if (!alive()) return;
                selectingQr = false;
                if (result == null) problem("No QR code found. Choose a clear photo showing the entire code.");
                else devices.reviewInvite(result);
            });
        });
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        boolean reopenMenu = menuOpen();
        if (reopenMenu) homePage.dismiss();
        render();
        if (reopenMenu) homePage.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putString("page", page);
        saved.putString("playbackTitle", playbackTitle);
        saved.putString("playbackProvider", playbackProvider);
        saved.putBoolean("menuOpen", menuOpen());
        saved.putString("provider", provider);
        saved.putString("query", query);
        saved.putString("liveChannel", livePage.selectedId);
        saved.putBoolean("selectingQr", selectingQr);
        if (devices.pendingInvite != null) saved.putString("invite", devices.pendingInvite);
    }

    @Override
    protected void onDestroy() {
        homePage.dismiss();
        devices.stop();
        catalogPage.close();
        livePage.stop();
        browse.shutdownNow();
        commands.execute(() -> tv.disconnect());
        commands.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        tvContext.start();
        if (page.equals("recordings")) recordingsPage.resume();
        if (page.equals("devices")) devices.start();
        if (guidePage()) livePage.resume();
        devices.reconnectOnOpen();
    }

    @Override
    protected void onStop() {
        tvContext.stop();
        recordingsPage.stop();
        devices.cancelReconnect();
        devices.stop();
        livePage.stop();
        super.onStop();
    }

    private boolean alive() {
        return !isDestroyed() && !isFinishing();
    }

    void render() {
        generation++;
        if (!page.equals("recordings")) recordingsPage.stop();
        if (!page.equals("devices")) devices.stop();
        if (!guidePage()) livePage.stop();
        root = ui.column();
        root.setBackgroundColor(PAPER);
        setContentView(root);
        header();
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        body = ui.column();
        int width = getResources().getConfiguration().screenWidthDp;
        int sides = Math.max(20, (width - 510) / 2);
        ui.pad(body, sides, 7, sides, 22);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        switch (page) {
            case "live": case "programme":
                livePage.build();
                break;
            case "catalog":
                if (provider.isEmpty()) catalogPage.index();
                else catalogPage.station();
                break;
            case "devices":
                devices.build();
                break;
            case "remote":
                body.addView(ui.title("Remote control", 42, INK));
                new RemoteControl(ui, this::sendKey).build(body, this::textDialog);
                break;
            case "recordings":
                recordingsPage.build();
                break;
            case "playback":
                buildPlayback();
                break;
            default:
                homePage.build();
        }
    }

    boolean guidePage() {
        return page.equals("live") || page.equals("programme");
    }

    boolean menuOpen() { return homePage.open(); }

    void restoreMenuFocus() {
        if (menuButton != null && !menuButton.isInTouchMode()) menuButton.requestFocus();
    }

    private void header() {
        LinearLayout h = ui.row();
        int side = Math.max(20, (getResources().getConfiguration().screenWidthDp - 510) / 2);
        ui.pad(h, side, 0, side, 0);
        FrameLayout menu = new FrameLayout(this);
        menu.addView(new Art(this, "menu", GREEN),
                new FrameLayout.LayoutParams(ui.d(24), ui.d(24), Gravity.CENTER));
        ui.action(menu, PALE, 16, LINE, homePage::show);
        menu.setContentDescription("Open menu");
        h.addView(menu, ui.lp(48, 48));
        menuButton = menu;
        ui.gap(h, 12);
        TextView brand = ui.text("OpenNava Companion", 16, INK, 700);
        h.addView(brand, ui.weight(44));
        LinearLayout chip = ui.row();
        chip.setGravity(Gravity.CENTER);
        ui.pad(chip, 11, 0, 11, 0);
        View dot = new View(this);
        chip.addView(dot, ui.lp(7, 7));
        ui.gap(chip, 6);
        TextView status = ui.text("", 12, GREEN, 650);
        status.setSingleLine();
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setMaxWidth(ui.d(120));
        chip.addView(status);
        ui.action(chip, PALE, 20, LINE, () -> go("devices"));
        connectionStatus = status;
        connectionDot = dot;
        connectionChip = chip;
        refreshConnectionHeader();
        FrameLayout target = new FrameLayout(this);
        target.addView(chip, new FrameLayout.LayoutParams(WRAP, ui.d(34), Gravity.CENTER));
        target.setOnClickListener(v -> go("devices"));
        h.addView(target, ui.lp(WRAP, 48));
        root.addView(h, ui.lp(MATCH, 61));
    }

    void refreshConnectionHeader() {
        if (tvContext != null) tvContext.refresh();
        if (connectionStatus == null) return;
        boolean up = connected();
        connectionStatus.setText(up ? device.name : devices.reconnecting() ? "Connecting…" : "Connect");
        connectionChip.setContentDescription(up ? "Connected to " + device.name : "Connect a TV");
        connectionDot.setBackground(ui.shape(up ? 0xff5b842e : 0xff8a8d80, 5, 0));
    }

    void go(String where) {
        page = where;
        if (where.equals("catalog")) { provider = ""; query = ""; }
        render();
    }

    void showTvProvider(String name, String term) {
        if (!("ard".equals(name) || "zdf".equals(name) || "arte".equals(name))) return;
        String search = term == null ? "" : term;
        if (page.equals("catalog") && provider.equals(name) && (term == null || query.equals(search))) return;
        page = "catalog";
        provider = name;
        query = search;
        render();
    }

    void showTvPage(String where) {
        if (!page.equals(where) || (where.equals("catalog") && !provider.isEmpty())) go(where);
    }

    void showTvPlayback(String name, String title) {
        String station = name == null ? "" : name;
        String episode = title == null ? "" : title;
        if (page.equals("playback") && playbackProvider.equals(station) && playbackTitle.equals(episode)) return;
        page = "playback";
        playbackProvider = station;
        playbackTitle = episode;
        render();
    }

    private void buildPlayback() {
        ui.space(body, 13);
        body.addView(ui.title("Playback", 42, INK));
        ui.space(body, 18);
        LinearLayout card = ui.column();
        card.setBackground(ui.shape(GREEN, 24, 0));
        ui.pad(card, 20, 20, 20, 21);
        String source = playbackProvider.isEmpty() ? "On TV" : playbackProvider.toUpperCase(java.util.Locale.GERMAN);
        card.addView(ui.text(source, 15, 0xffd6e8c2, 630));
        ui.space(card, 8);
        card.addView(ui.title(playbackTitle.isEmpty() ? "Programme playing" : playbackTitle, 34, WHITE));
        body.addView(card);
        ui.space(body, 13);
        body.addView(ui.button("Pause / Resume", "playpause", true, () -> sendKey(85)), ui.lp(MATCH, 58));
        ui.space(body, 8);
        LinearLayout seek = ui.row();
        seek.addView(ui.button("Rewind", "rewind", false, () -> sendKey(89)), ui.weight(54));
        ui.gap(seek, 8);
        seek.addView(ui.button("Fast forward", "forward", false, () -> sendKey(90)), ui.weight(54));
        body.addView(seek);
        ui.space(body, 12);
        String back = playbackProvider.isEmpty() ? "Back on TV" : "Back to library on TV";
        body.addView(ui.button(back, "back", false, () -> sendKey(4)), ui.lp(MATCH, 53));
    }

    boolean connected() {
        return device != null && tv.isConnected();
    }

    void openProvider(String name) {
        page = "catalog";
        provider = name;
        query = "";
        render();
        if (connected() && device.seniorVersion >= 20)
            runTv(
                    () -> {
                        tv.search(name, "");
                        return null;
                    },
                    null);
    }

    void requireMedia(Runnable run) {
        if (!connected()) {
            needConnection();
            return;
        }
        if (device.seniorVersion < 20) {
            new AlertDialog.Builder(this)
                    .setTitle("Set up the TV app")
                    .setMessage(
                            "To open this programme on your TV, install the"
                                + " updated TV app.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Go to device", (d, w) -> go("devices"))
                    .show();
            return;
        }
        run.run();
    }

    void sendSearch(String station, String term) {
        requireMedia(
                () ->
                        runTv(
                                () -> {
                                    tv.search(station, term);
                                    return null;
                                },
                                v -> say("Opened on TV.")));
    }

    void play(String station, String detail, String title, boolean live) {
        requireMedia(
                () ->
                        runTv(
                                () -> {
                                    tv.openMedia(station, detail, title, live);
                                    return null;
                                },
                                v -> say("Started on TV.")));
    }

    void sendKey(int key) {
        if (!connected()) {
            needConnection();
            return;
        }
        runTv(
                () -> {
                    tv.key(key);
                    return null;
                },
                null);
    }

    void needConnection() {
        if (android.os.SystemClock.elapsedRealtime() - lastNeedConnection < 450) return;
        lastNeedConnection = android.os.SystemClock.elapsedRealtime();
        go("devices");
        say("Choose the TV you want to control.");
    }

    void textDialog() {
        if (!connected()) {
            needConnection();
            return;
        }
        EditText input = field("Text for the active TV field", InputType.TYPE_CLASS_TEXT, "");
        LinearLayout box = ui.column();
        ui.pad(box, 24, 8, 24, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Send text")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Send",
                        (d, w) -> {
                            String text = input.getText().toString();
                            if (!text.isEmpty())
                                runTv(
                                        () -> {
                                            tv.sendText(text);
                                            return null;
                                        },
                                        v -> say("Text sent."));
                        })
                .show();
    }

    EditText field(String hint, int type, String value) {
        EditText e = new EditText(this);
        e.setSingleLine();
        e.setInputType(type);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setTextSize(16);
        e.setHint(hint);
        e.setText(value);
        e.setSelectAllOnFocus(true);
        e.setMinHeight(ui.d(50));
        return e;
    }

    void say(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    void problem(String message) {
        if (alive())
            new AlertDialog.Builder(this)
                    .setTitle("OpenNava Companion")
                    .setMessage(message)
                    .setPositiveButton("Close", null)
                    .show();
    }

    <T> void runTv(IO<T> job, Done<T> done) {
        runTv(job, done, null);
    }

    <T> void runTv(IO<T> job, Done<T> done, Done<Exception> fail) {
        commands.execute(
                () -> {
                    try {
                        T value = job.get();
                        runOnUiThread(
                                () -> {
                                    if (alive() && done != null) done.accept(value);
                                });
                    } catch (Exception e) {
                        runOnUiThread(
                                () -> {
                                    if (!alive()) return;
                                    if (fail != null) {
                                        fail.accept(e);
                                        return;
                                    }
                                    if (device != null && !tv.isConnected()) {
                                        device = null;
                                        render();
                                    }
                                    problem(error(e));
                                });
                    }
                });
    }

    String error(Exception e) {
        String msg = e.getLocalizedMessage();
        return msg == null || msg.trim().isEmpty()
                ? "The TV did not respond. Check the connection and try"
                      + " again."
                : msg;
    }

    @Override
    public void onBackPressed() {
        if (menuOpen()) homePage.dismiss();
        else if (page.equals("catalog") && !provider.isEmpty()) {
            provider = "";
            query = "";
            render();
        } else if (!page.equals("home")) go("home");
        else super.onBackPressed();
    }
}

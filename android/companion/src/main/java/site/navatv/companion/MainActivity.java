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
    LibraryHub hub;
    Images images;
    private LibraryPage libraryPage;
    LivePage livePage;
    private SettingsPage settingsPage;
    private LibrariesPage librariesPage;
    private StreamsPage streamsPage;
    private BackgroundsPage backgroundsPage;
    private StartPage startPage;
    private AppsPage appsPage;
    private NavaTubePage navaTubePage;
    TvApps apps;
    private LinearLayout banner;
    private FrameLayout miniSlot;
    private View miniShade;
    private MiniPlayer mini;
    private SetupFlow setup;
    private Tour tour;
    private HomeEditorPage homeEditor;
    private PhoneRecordingsPage recordingsPage;
    private PhoneTvContextController tvContext;
    DevicePage devices;
    String page = "home", provider = "ard", query = "";
    /** The television's last seen mode and library, so the phone only moves when the TV does. */
    private String followedTv = "";
    private long holdFollowUntil;
    int generation;
    boolean connecting, updating, selectingQr;
    private TextView connectionStatus;
    private TvConnection.Device streamsOwner;
    private String renderedPage = "";
    private LinearLayout connectionChip;
    private View connectionDot;
    private long lastNeedConnection;
    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(
            new ScanContract(), result -> {
                selectingQr = false;
                Intent data = result.getOriginalIntent();
                if (data != null && data.getBooleanExtra(QrScanActivity.EXTRA_HOME, false)) returnHome();
                else if (result.getContents() != null) receiveCode(result.getContents());
            });
    private final ActivityResultLauncher<PickVisualMediaRequest> qrPhoto = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) readQrPhoto(uri);
                else selectingQr = false;
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> backgroundPhotos = registerForActivityResult(
            new ActivityResultContracts.PickMultipleVisualMedia(BackgroundsPage.LIMIT),
            uris -> backgroundsPage.add(uris));

    void choosePhotos() {
        backgroundPhotos.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    String backgroundSummary() { return backgroundsPage.summary(); }

    String navaTubeSummary() { return navaTubePage.summary(); }

    String accountSummary() { return navaTubePage.accountSummary(); }

    private final ActivityResultLauncher<Intent> updates = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) returnHome();
            });

    void openUpdates() {
        updates.launch(new Intent(this, site.navatv.updates.UpdateActivity.class));
    }

    interface IO<T> {
        T get() throws Exception;
    }

    interface Done<T> {
        void accept(T data);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Region.apply(this);
        site.navatv.updates.Updates.home(this, this::openUpdates, this::returnHome);
        ui = new Surface(this);
        tv = new TvConnection(getApplicationContext());
        catalogue = new PhoneCatalog(getApplicationContext());
        hub = new LibraryHub(getApplicationContext(), catalogue);
        hub.setAmerican(Region.chosen(this) && "us".equals(Region.country(this)));
        images = new Images();
        libraryPage = new LibraryPage(this);
        livePage = new LivePage(this);
        settingsPage = new SettingsPage(this);
        librariesPage = new LibrariesPage(this);
        streamsPage = new StreamsPage(this);
        backgroundsPage = new BackgroundsPage(this);
        apps = new TvApps(this);
        startPage = new StartPage(this);
        appsPage = new AppsPage(this);
        navaTubePage = new NavaTubePage(this);
        mini = new MiniPlayer(this);
        mini.layoutChanged = this::makeRoom;
        mini.expandedChanged = this::shadePage;
        homeEditor = new HomeEditorPage(this);
        recordingsPage = new PhoneRecordingsPage(this);
        devices = new DevicePage(this);
        tvContext = new PhoneTvContextController(this);
        setup = new SetupFlow(this);
        tour = new Tour(this);
        if (state == null ? !setup.done() && !devices.hasSavedTv() : state.getBoolean("setup")) {
            if (state == null) setup.begin();
            else setup.restore(state.getString("setupInvite", ""));
        }
        if (state != null) {
            homeEditor.restoreDraft(state);
            page = state.getString("page", "home");
            provider = state.getString("provider", "ard");
            query = state.getString("query", "");
            livePage.selectedId = state.getString("liveChannel", "");
            selectingQr = state.getBoolean("selectingQr", false);
        }
        render();
        if (state != null && state.containsKey("invite")) devices.reviewInvite(state.getString("invite"));
        else consumeInvite(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeInvite(intent);
    }

    private void consumeInvite(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        String data = intent.getDataString();
        intent.setData(null);
        intent.setAction(Intent.ACTION_MAIN);
        receiveCode(data);
    }

    private void receiveCode(String code) {
        if (setup.waitingForCode()) setup.scanned(code);
        else if (!setup.active()) devices.reviewInvite(code);
    }

    /** Language or country changed: the pages, channels and libraries follow. */
    void regionChanged() {
        Region.apply(this);
        hub.setAmerican("us".equals(Region.country(this)));
        provider = hub.all().isEmpty() ? "ard" : hub.all().get(0).id;
        query = "";
        livePage.selectedId = "";
    }

    /** Hands language and country to the connected TV. */
    void sendRegion() {
        if (!connected() || !Region.chosen(this)) return;
        TvConnection connection = tv;
        String region = Region.value(this);
        runTv(() -> { connection.setLocale(region); return null; }, ignored -> tvContext.nudge(),
                ignored -> { /* an older TV app keeps its own language */ });
    }

    void runSetup() {
        tour.finish();
        setup.begin();
        render();
    }

    void setupEnded() {
        followedTv = "";
        if (!connected()) devices.reconnectOnOpen();
    }

    void startTour() {
        mini.expand(false);
        if (scroll != null) scroll.scrollTo(0, 0);
        tour.start();
    }

    void reportTourStep(int step) {
        if (!connected()) return;
        TvConnection connection = tv;
        runTv(() -> { connection.setupStep("tour-" + step); return null; }, ignored -> {}, ignored -> {});
    }

    /** The tour is over: the TV leaves its setup screen and shows its start page. */
    void finishTvSetup() {
        if (!connected()) return;
        TvConnection connection = tv;
        runTv(() -> {
            connection.setupStep("done");
            connection.openHome();
            return null;
        }, ignored -> tvContext.nudge(), ignored -> {});
    }

    void scanTv() {
        if (connecting || updating) return;
        devices.cancelReconnect();
        selectingQr = true;
        scanner.launch(new ScanOptions().setCaptureActivity(QrScanActivity.class)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
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
                else receiveCode(result);
            });
        });
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        homeEditor.saveDraft(saved);
        saved.putString("page", page);
        saved.putString("provider", provider);
        saved.putString("query", query);
        saved.putString("liveChannel", livePage.selectedId);
        saved.putBoolean("selectingQr", selectingQr);
        saved.putBoolean("setup", setup.active());
        saved.putString("setupInvite", setup.saved());
        if (devices.pendingInvite != null) saved.putString("invite", devices.pendingInvite);
    }

    @Override
    protected void onDestroy() {
        tvContext.close();
        devices.stop();
        libraryPage.close();
        apps.close();
        navaTubePage.close();
        mini.close();
        livePage.leave();
        images.close();
        catalogue.close();
        browse.shutdownNow();
        commands.execute(() -> tv.disconnect());
        commands.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        tvContext.start();
        if (page.equals("live") || page.equals("home") || page.equals("navatube") || page.equals("account")) render();
        if (page.equals("recordings")) recordingsPage.resume();
        if (page.equals("devices")) devices.start();
        followedTv = "";
        if (!setup.active()) devices.reconnectOnOpen();
    }

    @Override
    protected void onStop() {
        tvContext.stop();
        recordingsPage.stop();
        navaTubePage.stop();
        devices.cancelReconnect();
        devices.stop();
        livePage.leave();
        super.onStop();
    }

    private boolean alive() {
        return !isDestroyed() && !isFinishing();
    }

    private static boolean tabPage(String name) {
        return name.equals("home") || name.equals("live") || name.equals("library") || name.equals("remote");
    }

    private int sides() {
        return Math.max(20, (getResources().getConfiguration().screenWidthDp - 510) / 2);
    }

    void render() {
        generation++;
        if (!page.equals("recordings")) recordingsPage.stop();
        if (!page.equals("devices")) devices.stop();
        if (!page.equals("navatube") && !page.equals("account")) navaTubePage.stop();
        livePage.leave();
        int keptScroll = scroll != null && page.equals(renderedPage) ? scroll.getScrollY() : 0;
        renderedPage = page;
        if (!page.equals("home") && startPage.editing()) startPage.stopEditing();
        if (setup.active()) {
            View first = setup.view();
            if (first.getParent() == null) setContentView(first);
            return;
        }
        root = ui.column();
        root.setBackgroundColor(PAPER);
        setContentView(root);
        header();
        banner = ui.column();
        ui.pad(banner, sides(), 0, sides(), 0);
        root.addView(banner, ui.lp(MATCH, WRAP));
        body = ui.column();
        ui.pad(body, sides(), 4, sides(), page.equals("live") ? 0 : 22);
        scroll = null;
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        root.addView(stage, new LinearLayout.LayoutParams(MATCH, 0, 1));
        if (page.equals("live")) {
            // The wheel owns vertical swipes, so this page fits the screen instead of scrolling.
            stage.addView(body, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            scroll = new ScrollView(this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFillViewport(true);
            // The page scrolls on underneath the floating player.
            scroll.setClipToPadding(false);
            scroll.addView(body);
            stage.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        miniShade = new View(this);
        miniShade.setBackgroundColor(0xb3070810);
        miniShade.setOnClickListener(v -> mini.expand(false));
        stage.addView(miniShade, new FrameLayout.LayoutParams(MATCH, MATCH));
        miniSlot = new FrameLayout(this);
        miniSlot.setClipChildren(false);
        miniSlot.setClipToPadding(false);
        int side = Math.max(12, sides() - 8);
        ui.pad(miniSlot, side, 0, side, 10);
        stage.addView(miniSlot, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        mini.attach(miniSlot);
        shadePage();
        tabBar();
        switch (page) {
            case "library":
                libraryPage.build();
                break;
            case "remote":
                new RemoteControl(ui, this::sendKey).build(body, this::textDialog, this::openTvHome);
                break;
            case "settings":
                settingsPage.build();
                break;
            case "libraries":
                librariesPage.build();
                break;
            case "streams":
                streamsPage.build();
                break;
            case "home":
                startPage.build();
                break;
            case "live":
                livePage.build(body);
                break;
            case "apps":
                appsPage.build();
                break;
            case "navatube":
                navaTubePage.build();
                break;
            case "account":
                navaTubePage.buildAccount();
                break;
            case "backgrounds":
                backgroundsPage.build();
                break;
            case "devices":
                devices.build();
                break;
            case "personalization":
                homeEditor.build();
                break;
            case "recordings":
                recordingsPage.build();
                break;
            default:
                page = "home";
                startPage.build();
        }
        refreshBanner();
        refreshMiniBar();
        makeRoom();
        tour.attach();
        if (keptScroll > 0 && scroll != null) {
            ScrollView shown = scroll;
            shown.post(() -> shown.scrollTo(0, keptScroll));
        }
    }

    private void header() {
        LinearLayout h = ui.row();
        ui.pad(h, sides() - 6, 4, sides() - 6, 4);
        if (!tabPage(page)) {
            FrameLayout back = headerButton("left", "Back", this::onBackPressed);
            h.addView(back, ui.lp(48, 48));
            TextView title = ui.text(pageTitle(), 21, INK, 800);
            ui.pad(title, 6, 0, 0, 0);
            h.addView(title, ui.weight(48));
        } else {
            h.addView(ui.logo(34, INK), ui.lp(WRAP, 48));
            h.addView(new Space(this), ui.weight(1));
        }
        LinearLayout chip = ui.row();
        chip.setGravity(Gravity.CENTER);
        ui.pad(chip, 12, 0, 13, 0);
        View dot = new View(this);
        chip.addView(dot, ui.lp(8, 8));
        ui.gap(chip, 8);
        TextView status = ui.text("", 13, INK, 700);
        status.setSingleLine();
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setMaxWidth(ui.d(tabPage(page) ? 116 : 200));
        chip.addView(status);
        ui.action(chip, CARD, 20, LINE, () -> go("devices"));
        connectionStatus = status;
        connectionDot = dot;
        connectionChip = chip;
        if (tabPage(page)) {
            h.addView(chip, ui.lp(WRAP, 38));
            ui.gap(h, 4);
            h.addView(headerButton("settings", "Settings", () -> go("settings")), ui.lp(48, 48));
        }
        root.addView(h, ui.lp(MATCH, WRAP));
        refreshConnectionHeader();
    }

    private FrameLayout headerButton(String icon, String label, Runnable run) {
        FrameLayout button = new FrameLayout(this);
        button.addView(new Art(this, icon, INK), new FrameLayout.LayoutParams(ui.d(23), ui.d(23), Gravity.CENTER));
        ui.action(button, 0, 24, 0, run);
        button.setContentDescription(label);
        return button;
    }

    private String pageTitle() {
        switch (page) {
            case "libraries": return "My libraries";
            case "streams": return "My streams";
            case "backgrounds": return "Background images";
            case "apps": return "TV apps";
            case "navatube": return "NavaTube";
            case "account": return "YouTube account";
            case "devices": return "Connection";
            case "personalization": return "TV home";
            case "recordings": return "Recordings";
            default: return "Settings";
        }
    }

    private void tabBar() {
        LinearLayout bar = ui.row();
        bar.setBackgroundColor(CARD);
        ui.pad(bar, 10, 7, 10, 7);
        tabButton(bar, "home", "home", "Home");
        tabButton(bar, "live", "onair", "Live");
        tabButton(bar, "library", "library", "Library");
        tabButton(bar, "remote", "remote", "Remote");
        root.addView(bar, ui.lp(MATCH, WRAP));
    }

    private void tabButton(LinearLayout bar, String target, String icon, String label) {
        boolean selected = page.equals(target);
        LinearLayout item = ui.column();
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(ui.d(52));
        ui.action(item, selected ? 0xff2b2746 : 0, 18, 0, () -> {
            go(target);
            // Home on the phone also brings the TV to the start page of its chosen launcher.
            if (target.equals("home") && connected()) openTvHome();
        });
        item.addView(new Art(this, icon, selected ? LILAC : MUTED), ui.lp(21, 21));
        ui.space(item, 5);
        item.addView(ui.text(label, 11, selected ? LILAC : MUTED, selected ? 780 : 620), ui.lp(WRAP, WRAP));
        item.setSelected(selected);
        item.setContentDescription(label);
        bar.addView(item, ui.weight(WRAP));
    }

    PhoneTvContextController.Snapshot snapshot() {
        return connected() && tvContext != null ? tvContext.latest() : null;
    }

    /** Called when something on the television changed. */
    void tvChanged(PhoneTvContextController.Snapshot before, PhoneTvContextController.Snapshot now) {
        if (follow(now)) return;
        if (page.equals("live")) livePage.tvChanged();
        if (page.equals("home")) startPage.tvChanged();
        refreshBanner();
        refreshMiniBar();
    }

    /** Turn the phone into the layout that matches the TV. Returns true when the page was rebuilt. */
    private boolean follow(PhoneTvContextController.Snapshot now) {
        String mode = now.mode();
        String library = mode.equals("library") ? now.library() : "";
        String key = mode + "|" + library;
        // Neither the setup nor its tour may be moved away by what the TV does.
        if (setup.active() || tour.running() || startPage.editing()) return false;
        // Do not move the page away under an open dialog or submenu; try again with the next report.
        if (key.equals(followedTv) || !hasWindowFocus()) return false;
        boolean first = followedTv.isEmpty();
        followedTv = key;
        // While the TV leaves a closed player it may pass other screens; the phone stays on its start page.
        if (android.os.SystemClock.elapsedRealtime() < holdFollowUntil) return false;
        // The start page is where the viewer chooses; only a later change on the TV moves the phone away.
        boolean followable = tabPage(page) || page.equals("navatube");
        // Coming back from Google's sign-in page must not move the phone away from NavaTube either.
        if (mode.isEmpty() || !followable || (first && (page.equals("home") || page.equals("navatube")))) return false;
        if (mode.equals("navatube")) {
            if (page.equals("navatube")) return false;
            page = "navatube";
            render();
            return true;
        }
        if (mode.equals("live")) {
            if (page.equals("live")) return false;
            page = "live";
        } else {
            boolean sameLibrary = library.isEmpty() || library.equals(provider);
            if (page.equals("library") && sameLibrary) return false;
            page = "library";
            if (!sameLibrary) {
                provider = library;
                query = "";
            }
        }
        render();
        return true;
    }

    /** One line at the top when the TV needs attention: not connected, asleep or waiting for text. */
    private void refreshBanner() {
        if (banner == null) return;
        banner.removeAllViews();
        if (page.equals("devices")) return;
        PhoneTvContextController.Snapshot now = snapshot();
        if (!connected()) {
            notice(devices.reconnecting() ? "Looking for your TV…" : "No TV connected", "Connect", () -> go("devices"));
        } else if (now != null && !now.status.awake) {
            notice("Your TV is asleep", "Wake", () -> sendKey(224));
        } else if (now != null && now.status.keyboard) {
            notice("Your TV is waiting for text", "Type", this::textDialog);
        }
    }

    private void notice(String message, String action, Runnable run) {
        LinearLayout row = ui.row();
        row.setBackground(ui.shape(0xff3a2a24, 17, 0));
        ui.pad(row, 15, 5, 5, 5);
        row.addView(ui.text(message, 14, 0xffffd9c9, 700), ui.weight(WRAP));
        row.addView(ui.button(action, null, true, run), ui.lp(WRAP, 40));
        banner.addView(row);
        ui.space(banner, 8);
    }

    private void makeRoom() {
        if (body == null) return;
        int space = mini.reserved();
        if (scroll != null) scroll.setPadding(0, 0, 0, space);
        else body.setPadding(body.getPaddingLeft(), body.getPaddingTop(), body.getPaddingRight(), space);
    }

    private void shadePage() {
        if (miniShade == null) return;
        boolean open = mini.expanded();
        miniShade.animate().cancel();
        if (open) {
            miniShade.setAlpha(0);
            miniShade.setVisibility(View.VISIBLE);
            miniShade.animate().alpha(1).setDuration(220).start();
        } else miniShade.setVisibility(View.GONE);
    }

    /** The floating player for whatever the TV is playing, on every page. */
    private void refreshMiniBar() {
        if (miniSlot != null) mini.show(nowPlaying());
    }

    /** The TV reported again without anything new but the running position. */
    void playbackTick() {
        PhoneTvContextController.Snapshot now = snapshot();
        if (now != null && now.playback.present()) refreshMiniBar();
    }

    private MiniPlayer.State nowPlaying() {
        PhoneTvContextController.Snapshot now = snapshot();
        if (now == null || !now.status.awake) return null;
        MiniPlayer.State state = new MiniPlayer.State();
        // Plain media keys, for every player that has no commands of its own.
        state.toggle = () -> sendKey(85);
        state.back = () -> sendKey(89);
        state.forward = () -> sendKey(90);
        state.stop = () -> closePlayer(86);

        PhoneTvContextController.Playback video = now.playback;
        if (video.present()) {
            state.title = video.title.isEmpty() ? "Playing on TV" : video.title;
            state.playing = video.playing;
            state.live = video.live;
            state.position = video.position;
            state.duration = video.duration;
            state.measuredAt = video.at;
            // OpenNava's own players close with Back.
            state.stop = () -> closePlayer(4);
            String source = video.kind.equals("youtube") ? "NavaTube" : video.kind.equals("library") ? "Library" : "OpenNava";
            state.detail = video.author.isEmpty() ? source + " on your TV" : source + "  ·  " + video.author;
            state.key = video.kind + "|" + video.id + "|" + video.title;
            if (video.id.matches("[A-Za-z0-9_-]{11}")) {
                state.picture = "https://i.ytimg.com/vi/" + video.id + "/mqdefault.jpg";
                state.open = () -> go("navatube");
            } else state.asset = video.author.toUpperCase(java.util.Locale.ROOT).contains("ZDF") ? "zdf-cover.jpg"
                    : video.kind.equals("library") ? "ard-cover.jpg" : "home/forest.jpg";
            return state;
        }

        TvConnection.LiveState live = now.live();
        if (!live.channel.isEmpty()) {
            state.key = "live|" + live.channel;
            state.title = "Live TV";
            state.detail = "Live on your TV";
            for (site.navatv.launcher.PhoneGuide.Channel channel : channels()) {
                if (!channel.id.equals(live.channel)) continue;
                site.navatv.launcher.PhoneGuide.Show show = livePage.onAir(channel);
                state.title = show == null ? channel.name : show.title;
                state.detail = "Live  ·  " + channel.name;
                if (show != null && show.end > show.start) {
                    state.duration = show.end - show.start;
                    state.position = Math.max(0, System.currentTimeMillis() - show.start);
                    state.measuredAt = android.os.SystemClock.elapsedRealtime();
                }
            }
            state.live = true;
            state.playing = !live.paused;
            state.asset = "home/ocean.jpg";
            state.toggle = () -> liveControl("pause");
            state.back = () -> liveControl("rewind");
            state.forward = () -> liveControl("forward");
            state.stop = () -> closePlayer(4);
            state.open = () -> go("live");
            return state;
        }

        TvConnection.ScreenState screen = now.screen;
        boolean classicPlayer = screen != null && screen.screen.equals("player");
        String activity = now.status.activity;
        // A launcher that cannot report its player yet is still recognised by the screen in front.
        boolean ownPlayer = TvStatus.OPENNAVA_RUST.equals(now.status.app)
                && (activity.endsWith("NavaPlayerActivity") || activity.endsWith("StreamActivity"));
        if (now.status.hasMedia()) {
            String app = now.status.mediaApp;
            String name = TvStatus.appName(app);
            state.title = now.status.mediaTitle.isEmpty() ? name : now.status.mediaTitle;
            state.detail = now.status.mediaSubtitle.isEmpty() ? name + " on your TV" : name + "  ·  " + now.status.mediaSubtitle;
            state.playing = now.status.mediaPlaying;
            state.key = "media|" + app + "|" + state.title;
            state.app = apps.find(new String[] {app});
            if (state.app == null) {
                state.asset = "home/ocean.jpg";
                apps.refresh(false);
            }
            return state;
        }
        if (classicPlayer || ownPlayer) {
            state.title = classicPlayer && !screen.title.isEmpty() ? screen.title
                    : tvContext.playedTitle().isEmpty() ? "Playing on TV" : tvContext.playedTitle();
            state.detail = "OpenNava on your TV";
            state.key = "player|" + state.title;
            state.known = false;
            state.asset = "home/forest.jpg";
            state.stop = () -> closePlayer(4);
            return state;
        }
        if (now.status.inOtherApp()) {
            // The app tells Android nothing about its playback, so only its name and picture are certain.
            String app = now.status.app;
            state.app = apps.find(new String[] {app});
            state.title = state.app == null ? TvStatus.appName(app) : state.app.name;
            state.detail = "Open on your TV";
            state.key = "app|" + app;
            state.known = false;
            // Whether the app can skip at all is just as unknown, so no skip buttons promise it.
            state.back = null;
            state.forward = null;
            if (state.app == null) {
                state.asset = "home/ocean.jpg";
                apps.refresh(false);
            }
            return state;
        }
        return null;
    }

    void scheduleArrived() {
        if (!alive()) return;
        if (page.equals("live")) livePage.drawCard();
        else if (page.equals("home")) startPage.tvChanged();
        refreshMiniBar();
    }

    java.util.List<site.navatv.launcher.PhoneGuide.Channel> channels() {
        return site.navatv.launcher.PhoneGuide.channels(connected() && device.hasRtl);
    }

    java.util.List<String> recentChannels() {
        String saved = getSharedPreferences("channels", MODE_PRIVATE).getString("recent", "");
        return saved.isEmpty() ? new java.util.ArrayList<>() : java.util.Arrays.asList(saved.split(","));
    }

    void rememberChannel(String id) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        ids.add(id);
        ids.addAll(recentChannels());
        java.util.List<String> kept = new java.util.ArrayList<>(ids);
        getSharedPreferences("channels", MODE_PRIVATE).edit()
                .putString("recent", String.join(",", kept.subList(0, Math.min(6, kept.size())))).apply();
    }

    void tune(String channelId) {
        if (!connected()) { needConnection(); return; }
        if (!device.supportsLiveTune) { problem("Update the TV app to switch channels from your phone."); return; }
        TvConnection connection = tv;
        runTv(() -> { connection.tune(channelId); return null; }, done -> {
            rememberChannel(channelId);
            tvContext.nudge();
        });
    }

    void liveControl(String action) {
        if (!connected()) { needConnection(); return; }
        TvConnection connection = tv;
        runTv(() -> { connection.liveControl(action); return null; }, done -> tvContext.nudge());
    }

    void playOwn(LibraryHub.Card card) {
        libraryPage.play(card);
    }

    void openTvHome() { tvContext.openHome(); }

    /** Closing from the mini player returns both the phone and the TV to their start page. */
    private void closePlayer(int key) {
        if (!connected()) { needConnection(); return; }
        TvConnection connection = tv;
        holdFollowUntil = android.os.SystemClock.elapsedRealtime() + 6000;
        mini.expand(false);
        go("home");
        runTv(() -> {
            connection.key(key);
            // The player needs a moment to close before the launcher comes to the front.
            Thread.sleep(500);
            connection.openHome();
            return null;
        }, done -> tvContext.nudge());
    }

    void appsChanged() {
        if (!alive()) return;
        if ((page.equals("apps") || page.equals("home")) && !startPage.dragging()) render();
        else refreshMiniBar();
    }

    void openApp(TvApps.Entry app) {
        if (!connected()) { needConnection(); return; }
        TvConnection connection = tv;
        runTv(() -> { connection.launchApp(app.component); return null; }, done -> {
            say(app.name + " is opening on your TV.");
            tvContext.nudge();
        });
    }

    void openService(String name, String[] packages) {
        if (!connected()) { needConnection(); return; }
        TvApps.Entry known = apps.find(packages);
        if (known != null) { openApp(known); return; }
        TvConnection connection = tv;
        runTv(() -> { connection.launchPackage(packages); return null; }, done -> {
            say(name + " is opening on your TV.");
            tvContext.nudge();
        });
    }

    void openNavaTube() {
        if (!connected()) { needConnection(); return; }
        TvConnection connection = tv;
        runTv(() -> { connection.openNavaTube(); return null; }, done -> {
            say("NavaTube is opening on your TV.");
            tvContext.nudge();
        });
    }

    void played(String library, String title) { tvContext.played(library, title); }

    void showOwnStreams(java.util.List<site.navatv.launcher.CustomStreams.Entry> own) { streamsPage.show(own); }

    void showLibraries(java.util.List<site.navatv.launcher.OwnLibrary.Source> sources) {
        if (hub.update(sources) && (page.equals("library") || page.equals("libraries"))) render();
    }

    void streamsChanged() { tvContext.streamsChanged(); }

    void reloadLibraries() { tvContext.streamsChanged(); }

    void refreshConnectionHeader() {
        TvConnection.Device current = connected() ? device : null;
        if (current != streamsOwner) {
            streamsOwner = current;
            followedTv = "";
            if (streamsPage != null) streamsPage.forget();
        }
        if (tvContext != null) tvContext.refresh();
        refreshBanner();
        if (connectionStatus == null) return;
        boolean up = connected();
        connectionStatus.setText(up ? device.name : L.t(devices.reconnecting() ? "Connecting…" : "Connect"));
        connectionChip.setContentDescription(up ? "Connected to " + device.name : "Connect a TV");
        connectionDot.setBackground(ui.shape(up ? 0xff6fe3a1 : 0xff6b7286, 5, 0));
    }

    void returnHome() {
        View focused = getCurrentFocus();
        if (focused != null) getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(focused.getWindowToken(), 0);
        go("home");
    }

    void go(String where) {
        if (where.equals("personalization") && !page.equals(where)) homeEditor.enter();
        page = where;
        render();
    }

    boolean connected() {
        return device != null && tv.isConnected();
    }

    void openProvider(String name) {
        // Browsing on the phone leaves the TV where it is; only "Play on TV" moves it.
        page = "library";
        provider = name;
        query = "";
        render();
    }

    void requireMedia(Runnable run) {
        if (!connected()) {
            needConnection();
            return;
        }
        if (device.seniorVersion < 20) {
            homeDialog()
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
                done -> tvContext.nudge());
    }

    void needConnection() {
        if (android.os.SystemClock.elapsedRealtime() - lastNeedConnection < 450) return;
        lastNeedConnection = android.os.SystemClock.elapsedRealtime();
        go("devices");
        say("Choose the TV you want to control.");
    }

    AlertDialog.Builder homeDialog() {
        return new AlertDialog.Builder(this);
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
        homeDialog()
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
        Toast.makeText(this, L.t(message), Toast.LENGTH_SHORT).show();
    }

    void problem(String message) {
        if (alive())
            homeDialog()
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
        if (setup.active()) {
            if (!setup.back()) super.onBackPressed();
            return;
        }
        if (tour.running()) {
            tour.finish();
            return;
        }
        if (page.equals("home") && startPage.editing()) {
            startPage.edit(false);
            return;
        }
        if (mini.expanded()) {
            mini.expand(false);
            return;
        }
        if (page.equals("personalization") && homeEditor.back()) return;
        if (page.equals("settings") || page.equals("apps") || page.equals("navatube")) go("home");
        else if (!tabPage(page)) go("settings");
        else if (!page.equals("home")) go("home");
        else super.onBackPressed();
    }
}

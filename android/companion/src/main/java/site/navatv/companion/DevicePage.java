package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.app.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.text.InputType;
import android.view.View;
import android.widget.*;

import java.util.*;

final class DevicePage {
    final MainActivity a;
    final Surface u;
    final android.content.SharedPreferences prefs;
    final Discovery discovery;
    Map<String, Discovery.Endpoint> found = new HashMap<>();
    LinearLayout scanned;
    String pendingInvite;
    private AlertDialog inviteDialog;
    private AlertDialog addressDialog;
    private volatile TvConnection automatic;
    private java.util.concurrent.Future<?> autoJob;

    DevicePage(MainActivity a) {
        this.a = a;
        u = a.ui;
        prefs = a.getSharedPreferences("devices", Context.MODE_PRIVATE);
        discovery =
                new Discovery(
                        a,
                        endpoints -> {
                            found = endpoints;
                            refreshFound();
                        });
    }

    void start() {
        discovery.start();
    }

    void stop() {
        discovery.stop();
        found.clear();
    }

    void reconnectOnOpen() {
        if (automatic != null || a.connected() || a.connecting || a.updating || a.selectingQr
                || pendingInvite != null || addressDialog != null || prefs.getBoolean("disconnected", false)) return;
        String savedHost = prefs.getString("host", "");
        if (savedHost.isEmpty()) return;
        int port = prefs.getInt("port", 5555);
        String savedId = prefs.getString("connectedId", "");
        String savedName = prefs.getString("name", "");
        LinkedHashSet<String> routes = new LinkedHashSet<>();
        TvInvite invite = TvInvite.parse(prefs.getString("connectedInvite", prefs.getString("invite", "")));
        if (invite != null && matches(invite, savedHost, port)) {
            routes.add(preferredHost(invite));
            routes.add(savedHost);
            routes.add(invite.host);
            if (invite.lan != null) routes.add(invite.lan);
        } else {
            routes.add(savedHost);
        }
        TvConnection candidate = new TvConnection(a);
        automatic = candidate;
        a.refreshConnectionHeader();
        autoJob = a.browse.submit(() -> {
            boolean ready = false;
            try {
                for (String route : routes) {
                    if (automatic != candidate || Thread.currentThread().isInterrupted()) return;
                    try {
                        TvConnection.Device device = candidate.connect(route, port, true);
                        // With preferences from older versions we only have the last confirmed name.
                        boolean matchesTv = savedId.isEmpty() ? device.name.equals(savedName) : device.androidId.equals(savedId);
                        if (!matchesTv) continue;
                        if (automatic != candidate) return;
                        a.runOnUiThread(() -> {
                            if (automatic != candidate) return;
                            automatic = null;
                            autoJob = null;
                            TvConnection old = a.tv;
                            a.tv = candidate;
                            a.device = device;
                            remember(device);
                            a.commands.execute(old::disconnect);
                            if (a.page.equals("catalog")) a.refreshConnectionHeader();
                            else a.render();
                        });
                        ready = true;
                        return;
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception unavailable) {
                        // Try the other saved route to this TV, if available.
                    }
                }
            } finally {
                if (!ready) {
                    candidate.disconnect();
                    a.runOnUiThread(() -> {
                        if (automatic == candidate) {
                            automatic = null;
                            autoJob = null;
                            a.refreshConnectionHeader();
                        }
                    });
                }
            }
        });
    }

    void cancelReconnect() {
        TvConnection candidate = automatic;
        if (candidate == null) return;
        automatic = null;
        a.refreshConnectionHeader();
        if (autoJob != null) autoJob.cancel(true);
        autoJob = null;
        a.commands.execute(candidate::disconnect);
    }

    boolean reconnecting() {
        return automatic != null;
    }

    private void remember(TvConnection.Device device) {
        android.content.SharedPreferences.Editor edit = prefs.edit()
                .putBoolean("lastLineupHasRtl", device.hasRtl)
                .putString("host", device.host).putInt("port", device.port)
                .putString("name", device.name).putString("connectedId", device.androidId)
                .remove("disconnected");
        String raw = prefs.getString("invite", "");
        TvInvite invite = TvInvite.parse(raw);
        List<SavedTv> tvs = SavedTv.list(prefs);
        String routes = invite != null && matches(invite, device.host, device.port) ? raw : "";
        Iterator<SavedTv> prior = tvs.iterator();
        while (prior.hasNext()) {
            SavedTv tv = prior.next();
            boolean legacyRoute = tv.id.isEmpty() && tv.name.equals(device.name) && invite != null
                    && matches(invite, device.host, device.port) && matches(invite, tv.host, tv.port);
            if (!tv.sameDevice(device) && !legacyRoute) continue;
            if (routes.isEmpty()) routes = tv.invite;
            prior.remove();
        }
        tvs.add(0, new SavedTv(device.androidId, device.host, device.port, device.name, routes));
        edit.putString("connectedInvite", routes).putString("knownTvs", SavedTv.encode(tvs));
        edit.apply();
        String checked = "lastUpdateCheck." + device.androidId;
        if (device.seniorVersion > 0 && site.navatv.updates.Updates.enabled(a)
                && System.currentTimeMillis() - prefs.getLong(checked, 0) > java.util.concurrent.TimeUnit.HOURS.toMillis(12)) {
            TvConnection connection = a.tv;
            a.browse.execute(() -> {
                try {
                    String result = connection.updatePairedTv(device, true);
                    if (result.equals("The TV is up to date.") || result.endsWith("installed on the TV."))
                        prefs.edit().putLong(checked, System.currentTimeMillis()).apply();
                } catch (Exception ignored) { /* Retry on the next connection. */ }
            });
        }
    }

    void build() {
        a.body.addView(u.title("Devices", 44, INK));
        u.space(a.body, 15);
        if (a.connected()) active();
        else {
            LinearLayout card = u.column();
            u.pad(card, 20, 18, 20, 18);
            card.setBackground(u.shape(GREEN, 24, 0));
            card.addView(new Art(a, "tv", APRICOT), u.lp(27, 27));
            u.space(card, 13);
            card.addView(u.title("Connect a TV", 31, WHITE));
            u.space(card, 5);
            card.addView(
                    u.text(
                            "Control your TV over Wi-Fi or Tailscale."
                                + " Enable ADB debugging on your TV.",
                            14,
                            0xffdbe3d9,
                            480));
            a.body.addView(card);
        }
        if (a.connecting || a.updating) {
            u.space(a.body, 15);
            a.body.addView(
                    u.text(
                            a.updating ? "Transferring TV app…" : "Connecting…",
                            15,
                            ACCENT,
                            650));
        }
        u.space(a.body, 18);
        a.body.addView(row("Scan QR code", "Open the connection code in the full launcher",
                "scan", a::scanTv));
        u.space(a.body, 7);
        a.body.addView(row("Open a QR code from a photo", "Choose a photo of your TV",
                "photo", a::chooseQrPhoto));
        String stored = prefs.getString("invite", "");
        TvInvite savedInvite = TvInvite.parse(stored);
        List<SavedTv> tvs = SavedTv.list(prefs);
        boolean alreadyKnown = savedInvite != null && tvs.stream().anyMatch(tv -> matches(savedInvite, tv.host, tv.port));
        if (savedInvite != null && !alreadyKnown) {
            u.space(a.body, 18);
            a.body.addView(u.text("Saved TV", 18, INK, 710));
            u.space(a.body, 7);
            a.body.addView(row(savedInvite.name, connectionLabel(preferredHost(savedInvite), savedInvite.port),
                    "tv", () -> reviewInvite(stored)));
        }
        if (!tvs.isEmpty()) {
            u.space(a.body, 18);
            a.body.addView(u.text("My TVs", 18, INK, 710));
            for (SavedTv tv : tvs) {
                boolean current = a.connected() && tv.sameDevice(a.device);
                TvInvite addresses = TvInvite.parse(tv.invite);
                String host = addresses != null && matches(addresses, tv.host, tv.port)
                        ? preferredHost(addresses) : tv.host;
                u.space(a.body, 7);
                a.body.addView(row(tv.name, (current ? "Connected · " : "") + host + ":" + tv.port,
                        current ? "ok" : "tv", () -> {
                            if (current) a.go("remote");
                            else connect(host, tv.port, tv);
                        }));
            }
        }
        u.space(a.body, 22);
        a.body.addView(u.text("Found on Wi-Fi", 18, INK, 720));
        u.space(a.body, 8);
        scanned = u.column();
        a.body.addView(scanned);
        refreshFound();
        String saved = prefs.getString("host", "");
        int p = prefs.getInt("port", 5555);
        u.space(a.body, 18);
        a.body.addView(u.text("Connect manually", 18, INK, 710));
        u.space(a.body, 7);
        a.body.addView(
                row(
                        "Enter address",
                        "Wi-Fi address, Tailscale IP or device name",
                        "arrow",
                        () -> address(false, saved, p)));
        u.space(a.body, 7);
        a.body.addView(
                row(
                        "Pair with code",
                        "Google TV · Wireless debugging",
                        "plus",
                        () -> address(true, saved, 0)));
        start();
    }

    private void active() {
        TvConnection.Device d = a.device;
        LinearLayout card = u.column();
        card.setBackground(u.shape(GREEN, 24, 0));
        u.pad(card, 20, 20, 20, 18);
        LinearLayout status = u.row();
        View dot = new View(a);
        dot.setBackground(u.shape(0xffbddea0, 6, 0));
        status.addView(dot, u.lp(8, 8));
        u.gap(status, 7);
        status.addView(u.text("Connected", 12, 0xffdbe7d4, 620));
        card.addView(status);
        u.space(card, 9);
        card.addView(u.title(d.name, 34, WHITE));
        u.space(card, 4);
        card.addView(u.text(d.host + " · Port " + d.port, 14, 0xffd8e1d6, 480));
        u.space(card, 15);
        LinearLayout actions = u.row();
        actions.addView(u.button("Control", "remote", false, () -> a.go("remote")), u.weight(48));
        u.gap(actions, 8);
        actions.addView(
                u.button(
                        "Disconnect",
                        null,
                        false,
                        () -> {
                            if (a.updating) {
                                a.say("The TV app is being transferred.");
                                return;
                            }
                            prefs.edit().putBoolean("disconnected", true).apply();
                            cancelReconnect();
                            a.runTv(
                                    () -> {
                                        a.tv.disconnect();
                                        return null;
                                    },
                                    v -> {
                                        a.device = null;
                                        a.render();
                                    });
                        }),
                u.weight(48));
        card.addView(actions);
        a.body.addView(card);
        if (d.seniorVersion > 0) {
            u.space(a.body, 8);
            a.body.addView(row("TV software updates", "Check the paired TV for a stable release.", "arrow", () ->
                    new AlertDialog.Builder(a).setTitle("Update this TV?")
                            .setMessage("A new release will restart OpenNava. Your profile, settings and recordings are kept.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Check and install", (dialog, which) -> {
                                TvConnection connection = a.tv;
                                a.runTv(() -> connection.updatePairedTv(d, false), result -> a.say(result));
                            }).show()));
        }
        if (d.canUpdateSenior) {
            u.space(a.body, 18);
            boolean fresh = d.seniorVersion < 0;
            a.body.addView(
                    row(
                            fresh ? "Install TV app" : "Update TV app",
                            fresh
                                    ? "Launch programmes directly from your phone."
                                    : "Choose programmes from your phone.",
                            "arrow",
                            () -> update(fresh)));
        } else if (d.seniorVersion >= 20) {
            u.space(a.body, 12);
            a.body.addView(u.text("This TV supports programme selection.", 14, MUTED, 540));
        }
        if (d.seniorVersion >= 20 && d.homeMigrationPending) {
            u.space(a.body, 10);
            a.body.addView(
                    row(
                            "Set up Home button",
                            "Launch the updated TV app.",
                            "home",
                            () ->
                                    new AlertDialog.Builder(a)
                                            .setTitle("Set up the Home button?")
                                            .setMessage(
                                                    "The Home button on this Fire TV will open"
                                                        + " the updated OpenNava launcher.")
                                            .setNegativeButton("Cancel", null)
                                            .setPositiveButton(
                                                    "Apply",
                                                    (dialog, w) -> {
                                                        a.updating = true;
                                                        a.render();
                                                        a.runTv(
                                                                () -> a.tv.migrateFireHome(),
                                                                next -> {
                                                                    a.device = next;
                                                                    a.updating = false;
                                                                    a.render();
                                                                },
                                                                e -> {
                                                                    a.updating = false;
                                                                    a.device = a.tv.device();
                                                                    a.render();
                                                                    a.problem(a.error(e));
                                                                });
                                                    })
                                            .show()));
        }
    }

    private static boolean matches(TvInvite invite, String host, int port) {
        return port == invite.port && (host.equals(invite.host) || host.equals(invite.lan));
    }

    private String preferredHost(TvInvite invite) {
        if (invite.lan == null) return invite.host;
        ConnectivityManager connectivity = a.getSystemService(ConnectivityManager.class);
        NetworkCapabilities network = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (network != null && network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !network.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return invite.lan;
        return invite.host;
    }

    private String connectionLabel(String host, int port) {
        return (host.startsWith("100.") ? "Tailscale" : "Wi-Fi") + " · " + host + ":" + port;
    }

    void reviewInvite(String raw) {
        if (a.connecting || a.updating) {
            a.say("Wait for the current operation to finish.");
            return;
        }
        if (inviteDialog != null) inviteDialog.dismiss();
        cancelReconnect();
        a.go("devices");
        TvInvite invite = TvInvite.parse(raw);
        if (invite == null) {
            a.problem("This code has no valid TV address. Open the connection code in the full launcher.");
            return;
        }
        pendingInvite = raw;
        LinearLayout form = u.column();
        u.pad(form, 24, 12, 24, 0);
        form.addView(u.text(invite.name, 19, INK, 710));
        u.space(form, 8);
        RadioGroup routes = new RadioGroup(a);
        if (invite.lan == null) {
            form.addView(u.text(connectionLabel(invite.host, invite.port), 15, MUTED, 520));
        } else {
            String preferred = preferredHost(invite);
            for (String host : new String[]{invite.host, invite.lan}) {
                RadioButton radio = new RadioButton(a);
                radio.setId(View.generateViewId());
                radio.setTag(host);
                radio.setText(connectionLabel(host, invite.port));
                routes.addView(radio);
                if (host.equals(preferred)) routes.check(radio.getId());
            }
            form.addView(routes);
        }
        u.space(form, 10);
        form.addView(u.text("Your TV may ask you to approve this connection.", 14, MUTED, 480));
        inviteDialog = new AlertDialog.Builder(a)
                .setTitle("Connect to this TV?")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Save for later", (d, w) -> {
                    prefs.edit().putString("invite", raw).apply();
                    a.render();
                    a.say("TV saved.");
                })
                .setPositiveButton("Connect", (d, w) -> {
                    String host = invite.host;
                    if (invite.lan != null) host = (String) routes.findViewById(routes.getCheckedRadioButtonId()).getTag();
                    prefs.edit().putString("invite", raw).apply();
                    connect(host, invite.port);
                }).create();
        AlertDialog current = inviteDialog;
        current.setOnDismissListener(d -> {
            if (inviteDialog == current) {
                inviteDialog = null;
                pendingInvite = null;
            }
        });
        current.show();
    }

    private void update(boolean fresh) {
        if (a.updating || a.connecting || !a.connected()) return;
        String name = a.device.name;
        new AlertDialog.Builder(a)
                .setTitle(fresh ? "Install TV app?" : "Update TV app?")
                .setMessage(
                        (fresh ? "Install OpenNava on " : "Update the installed TV app on ")
                                + name
                                + (fresh
                                        ? ". You can then launch programmes directly from"
                                              + " your phone."
                                        : ". Your saved"
                                              + " settings will stay in place.")
                                + (a.device.homeMigrationPending
                                        ? " The existing OpenNava Home button setting will be carried over."
                                        : ""))
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        fresh ? "Install" : "Update",
                        (dialog, which) -> {
                            a.updating = true;
                            a.render();
                            a.runTv(
                                    () -> a.tv.updateSenior(),
                                    d -> {
                                        a.updating = false;
                                        a.device = d;
                                        a.render();
                                        a.say("The TV app is ready.");
                                    },
                                    e -> {
                                        a.updating = false;
                                        a.device = a.tv.device();
                                        a.render();
                                        a.problem(a.error(e));
                                    });
                        })
                .show();
    }

    private LinearLayout row(String title, String detail, String icon, Runnable onTap) {
        LinearLayout b = u.row();
        b.setMinimumHeight(u.d(67));
        u.pad(b, 15, 10, 12, 10);
        b.addView(new Art(a, icon, GREEN), u.lp(23, 23));
        u.gap(b, 12);
        LinearLayout t = u.column();
        t.addView(u.text(title, 15, INK, 700));
        u.space(t, 3);
        t.addView(u.text(detail, 13, MUTED, 450));
        b.addView(t, u.weight(WRAP));
        u.gap(b, 4);
        b.addView(new Art(a, "right", MUTED), u.lp(16, 16));
        u.action(b, WHITE, 17, LINE, onTap);
        return b;
    }

    private void refreshFound() {
        if (scanned == null || !a.page.equals("devices")) return;
        scanned.removeAllViews();
        if (found.isEmpty()) {
            TextView t = u.text("TVs on your Wi-Fi appear here. Over Tailscale, scan the TV’s QR code or open it from a photo.", 14, MUTED, 480);
            u.pad(t, 0, 6, 0, 6);
            scanned.addView(t);
            return;
        }
        Set<String> distinct = new HashSet<>();
        List<Discovery.Endpoint> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparing(e -> e.host + e.port));
        for (Discovery.Endpoint e : list) {
            if (!distinct.add(e.host + ":" + e.port)) continue;
            String title = e.pair ? "Pair Google TV" : "TV · " + e.host;
            scanned.addView(
                    row(
                            title,
                            e.name + " · Port " + e.port,
                            e.pair ? "plus" : "tv",
                            () -> {
                                if (e.pair) address(true, e.host, e.port);
                                else connect(e.host, e.port);
                            }));
            u.space(scanned, 5);
        }
    }

    private void connect(String host, int port) {
        connect(host, port, null);
    }

    private void connect(String host, int port, SavedTv savedTv) {
        if (a.updating || a.connecting) return;
        cancelReconnect();
        a.connecting = true;
        a.render();
        a.runTv(
                () -> {
                    TvConnection.Device result = a.tv.connect(host, port);
                    if (savedTv != null && (!savedTv.id.isEmpty() ? !savedTv.id.equals(result.androidId) : !savedTv.name.equals(result.name))) {
                        a.tv.disconnect();
                        throw new java.io.IOException("A different TV is using this saved address. Scan your TV’s current QR code.");
                    }
                    return result;
                },
                d -> {
                    a.device = d;
                    a.connecting = false;
                    remember(d);
                    a.render();
                    a.say("Connected to " + d.name + ".");
                },
                e -> {
                    a.connecting = false;
                    if (!a.tv.isConnected()) a.device = null;
                    a.render();
                    TvInvite saved = TvInvite.parse(prefs.getString("invite", ""));
                    if (saved == null || !matches(saved, host, port)) {
                        a.problem(a.error(e));
                        return;
                    }
                    new AlertDialog.Builder(a).setTitle("Connection pending")
                            .setMessage(a.error(e) + " Your TV is saved. On Google TV, open Wireless debugging to get a pairing code.")
                            .setNegativeButton("Close", null)
                            .setPositiveButton("Pair with code", (d, w) -> address(true, host, 0))
                            .show();
                });
    }

    private void address(boolean pair, String defaultHost, int defaultPort) {
        if (a.updating || a.connecting) return;
        cancelReconnect();
        LinearLayout form = u.column();
        u.pad(form, 23, 9, 23, 0);
        String info =
                pair
                        ? "On Google TV, open Wireless debugging → Pair device with pairing code."
                              + " When using Tailscale, enter the TV’s Tailscale address together with"
                              + " the pairing port and code shown on your TV."
                        : "When away from home, connect Tailscale on your TV and phone first."
                              + " Enter your TV’s Tailscale IP or MagicDNS name."
                              + " On Google TV, find the current port under Wireless debugging."
                              + " Fire TV usually uses 5555.";
        form.addView(u.text(info, 14, MUTED, 480));
        u.space(form, 10);
        EditText host =
                a.field(
                        "IP address or device name",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                        defaultHost);
        form.addView(host);
        EditText port =
                a.field(
                        pair ? "Pairing port" : "Connection port",
                        InputType.TYPE_CLASS_NUMBER,
                        defaultPort > 0 ? String.valueOf(defaultPort) : "");
        form.addView(port);
        EditText code = a.field("Six-digit pairing code", InputType.TYPE_CLASS_NUMBER, "");
        if (pair) form.addView(code);
        AlertDialog d =
                new AlertDialog.Builder(a)
                        .setTitle(pair ? "Pair Google TV" : "Connect a TV")
                        .setView(form)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(pair ? "Pair" : "Connect", null)
                        .create();
        addressDialog = d;
        d.setOnDismissListener(dialog -> { if (addressDialog == d) addressDialog = null; });
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            String h = host.getText().toString().trim(),
                                    token = code.getText().toString().trim();
                            int number;
                            if (h.isEmpty() || h.matches(".*[\\s/].*")) {
                                host.setError("Enter a valid address");
                                return;
                            }
                            try {
                                number = Integer.parseInt(port.getText().toString());
                                if (number < 1 || number > 65535) throw new NumberFormatException();
                            } catch (NumberFormatException bad) {
                                port.setError("Enter a port between 1 and 65535");
                                return;
                            }
                            if (pair && !token.matches("[0-9]{6}")) {
                                code.setError("Enter six digits");
                                return;
                            }
                            d.dismiss();
                            if (!pair) {
                                connect(h, number);
                                return;
                            }
                            a.connecting = true;
                            a.render();
                            a.runTv(
                                    () -> {
                                        a.tv.pair(h, number, token);
                                        return null;
                                    },
                                    ok -> {
                                        a.connecting = false;
                                        a.render();
                                        Optional<Discovery.Endpoint> endpoint =
                                                found.values().stream()
                                                        .filter(x -> x.host.equals(h) && !x.pair)
                                                        .findFirst();
                                        TvInvite invite = TvInvite.parse(prefs.getString("invite", ""));
                                        if (endpoint.isPresent()) connect(h, endpoint.get().port);
                                        else if (invite != null && (h.equals(invite.host) || h.equals(invite.lan))) {
                                            connect(h, invite.port);
                                        } else {
                                            a.say(
                                                    "Paired. Enter the connection port from the main"
                                                        + " Wireless debugging screen.");
                                            address(false, h, 0);
                                        }
                                    },
                                    e -> {
                                        a.connecting = false;
                                        a.render();
                                        a.problem(a.error(e));
                                    });
                        });
    }
}

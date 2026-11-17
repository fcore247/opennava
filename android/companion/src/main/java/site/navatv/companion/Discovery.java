package site.navatv.companion;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Finds only devices advertising the Android wireless debugging services. */
final class Discovery {
    interface Listener {
        void devices(Map<String, Endpoint> endpoints);
    }

    static final class Endpoint {
        final String name, host;
        final int port;
        final boolean pair;

        Endpoint(NsdServiceInfo s, boolean pair) {
            name = s.getServiceName();
            host = s.getHost().getHostAddress();
            port = s.getPort();
            this.pair = pair;
        }
    }

    private final NsdManager nsd;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> subscriptions = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private final Map<String, Endpoint> results = new HashMap<>();
    private boolean running, resolving;
    private int generation;

    Discovery(Context c, Listener l) {
        nsd = c.getSystemService(NsdManager.class);
        listener = l;
    }

    void start() {
        if (running || nsd == null) return;
        running = true;
        final int current = ++generation;
        for (String type :
                new String[] {"_adb._tcp.", "_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp."}) {
            NsdManager.DiscoveryListener d =
                    new NsdManager.DiscoveryListener() {
                        public void onStartDiscoveryFailed(String t, int e) {}

                        public void onStopDiscoveryFailed(String t, int e) {}

                        public void onDiscoveryStarted(String t) {}

                        public void onDiscoveryStopped(String t) {}

                        public void onServiceFound(NsdServiceInfo s) {
                            ui.post(
                                    () -> {
                                        if (generation == current) {
                                            pending.offer(s);
                                            resolveNext(current);
                                        }
                                    });
                        }

                        public void onServiceLost(NsdServiceInfo s) {
                            ui.post(
                                    () -> {
                                        if (generation == current
                                                && results.remove(
                                                                s.getServiceName()
                                                                        + s.getServiceType())
                                                        != null)
                                            listener.devices(new HashMap<>(results));
                                    });
                        }
                    };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, d);
                subscriptions.add(d);
            } catch (RuntimeException unavailable) {
                /* Manual entry also works without multicast. */
            }
        }
    }

    private void resolveNext(int current) {
        if (resolving || pending.isEmpty() || !running || generation != current) return;
        NsdServiceInfo next = pending.poll();
        resolving = true;
        try {
            nsd.resolveService(
                    next,
                    new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo s, int error) {
                            ui.post(() -> done(current, next, null));
                        }

                        public void onServiceResolved(NsdServiceInfo s) {
                            ui.post(() -> done(current, next, s));
                        }
                    });
        } catch (RuntimeException unavailable) {
            done(current, next, null);
        }
    }

    private void done(int current, NsdServiceInfo advertised, NsdServiceInfo resolved) {
        if (current != generation) return;
        if (resolved != null && resolved.getHost() != null && resolved.getPort() > 0) {
            results.put(
                    advertised.getServiceName() + advertised.getServiceType(),
                    new Endpoint(resolved, advertised.getServiceType().contains("pairing")));
            listener.devices(new HashMap<>(results));
        }
        resolving = false;
        resolveNext(current);
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        pending.clear();
        results.clear();
        resolving = false;
        for (NsdManager.DiscoveryListener d : subscriptions)
            try {
                nsd.stopServiceDiscovery(d);
            } catch (RuntimeException stopped) {
            }
        subscriptions.clear();
    }
}

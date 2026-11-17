package site.navatv.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Finds a local ADB connection endpoint without authenticating or opening a shell. */
final class PhoneLinkAddress {
    String tail, lan;
    int port;

    static PhoneLinkAddress find(Context context, int suppliedPort) throws Exception {
        PhoneLinkAddress result = new PhoneLinkAddress();
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                LinkProperties link = cm.getLinkProperties(n);
                if (caps == null || link == null) continue;
                boolean vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                boolean local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                if (vpn || local) {
                    for (LinkAddress address : link.getLinkAddresses()) result.add(address.getAddress(), vpn);
                }
            }
        }
        // Some TV builds omit the VPN from ConnectivityManager's network list.
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            String name = nic.getName();
            boolean tun = name.startsWith("tun") || name.startsWith("tailscale");
            if (tun || name.startsWith("wlan") || name.startsWith("eth")) {
                for (InetAddress ip : Collections.list(nic.getInetAddresses())) result.add(ip, tun);
            }
        }
        if (result.host() == null) return result;
        Set<Integer> candidates = new LinkedHashSet<>();
        if (suppliedPort > 0) candidates.add(suppliedPort);
        else {
            candidates.add(propertyPort("service.adb.tcp.port"));
            candidates.add(propertyPort("service.adb.tls.port"));
            candidates.add(5555);
        }
        for (int p : candidates) {
            if (p > 0 && result.acceptsAdb(p)) {
                result.port = p;
                return result;
            }
        }
        if (suppliedPort == 0) result.port = result.advertisedPort(context);
        return result;
    }

    private void add(InetAddress address, boolean vpn) {
        if (!(address instanceof Inet4Address)) return;
        byte[] raw = address.getAddress();
        if (vpn && raw[0] == 100 && (raw[1] & 192) == 64) tail = address.getHostAddress();
        else if (!vpn && lan == null && address.isSiteLocalAddress()) lan = address.getHostAddress();
    }

    String host() { return tail != null ? tail : lan; }

    Uri invitation(Context context) {
        String name = Settings.Global.getString(context.getContentResolver(), "device_name");
        if (name == null || name.trim().isEmpty()) name = Build.MODEL;
        name = name.trim();
        if (name.length() > 48) name = name.substring(0, 48);
        Uri.Builder builder = new Uri.Builder().scheme("navatv").authority("connect")
                .appendQueryParameter("v", "1").appendQueryParameter("host", host())
                .appendQueryParameter("port", Integer.toString(port));
        if (tail != null && lan != null) builder.appendQueryParameter("lan", lan);
        return builder.appendQueryParameter("name", name).build();
    }

    private static int propertyPort(String key) {
        try {
            Process p = new ProcessBuilder("/system/bin/getprop", key).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                int port = Integer.parseInt(reader.readLine());
                return port > 0 && port <= 65535 ? port : 0;
            } finally { p.destroy(); }
        } catch (Exception unavailable) { return 0; }
    }

    private boolean acceptsAdb(int port) {
        // A local service bound to port 5555 alone is not sufficient to advertise it as ADB.
        String[] addresses = { "127.0.0.1", lan != null ? lan : host() };
        for (String address : addresses) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(address, port), 500);
                s.setSoTimeout(1000);
                final int cnxn = 0x4e584e43, auth = 0x48545541, stls = 0x534c5453;
                byte[] hostId = { 'h', 'o', 's', 't', ':', ':', 0 };
                int checksum = 0;
                for (byte b : hostId) checksum += b;
                ByteBuffer hello = ByteBuffer.allocate(24 + hostId.length).order(ByteOrder.LITTLE_ENDIAN);
                hello.putInt(cnxn).putInt(0x01000001).putInt(4096).putInt(hostId.length)
                        .putInt(checksum).putInt(~cnxn).put(hostId);
                s.getOutputStream().write(hello.array());
                byte[] header = new byte[24];
                new DataInputStream(s.getInputStream()).readFully(header);
                ByteBuffer reply = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int cmd = reply.getInt();
                if ((cmd == auth || cmd == cnxn || cmd == stls) && reply.getInt(20) == ~cmd) return true;
            } catch (Exception unavailable) { /* Try the next local interface. */ }
        }
        return false;
    }

    private int advertisedPort(Context context) throws InterruptedException {
        NsdManager nsd = context.getSystemService(NsdManager.class);
        if (nsd == null) return 0;
        BlockingQueue<NsdServiceInfo> services = new LinkedBlockingQueue<>();
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) {}
            public void onDiscoveryStopped(String type) {}
            public void onStartDiscoveryFailed(String type, int error) {}
            public void onStopDiscoveryFailed(String type, int error) {}
            public void onServiceLost(NsdServiceInfo info) {}
            public void onServiceFound(NsdServiceInfo info) { services.offer(info); }
        };
        long deadline = android.os.SystemClock.elapsedRealtime() + 4500;
        try {
            nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
            long remaining;
            while ((remaining = deadline - android.os.SystemClock.elapsedRealtime()) > 0) {
                NsdServiceInfo service = services.poll(remaining, TimeUnit.MILLISECONDS);
                if (service == null) break;
                CountDownLatch ready = new CountDownLatch(1);
                final int[] port = {0};
                nsd.resolveService(service, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo info, int error) { ready.countDown(); }
                    public void onServiceResolved(NsdServiceInfo info) {
                        String ip = info.getHost() == null ? "" : info.getHost().getHostAddress();
                        if (ip.equals(lan) || ip.equals(tail)) port[0] = info.getPort();
                        ready.countDown();
                    }
                });
                if (ready.await(Math.min(remaining, 1500), TimeUnit.MILLISECONDS)
                        && port[0] > 0 && acceptsAdb(port[0])) return port[0];
            }
        } catch (RuntimeException unsupported) {
            // The connection port can also be entered from the TV's developer settings.
        } finally {
            try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException stopped) {}
        }
        return 0;
    }
}

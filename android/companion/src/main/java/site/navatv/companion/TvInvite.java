package site.navatv.companion;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/** A visitor-mode QR code contains the TV's connection addresses. */
final class TvInvite {
    final String host, lan, name;
    final int port;

    private TvInvite(String host, String lan, String name, int port) {
        this.host = host;
        this.lan = lan;
        this.name = name;
        this.port = port;
    }

    static TvInvite parse(String text) {
        if (text == null || text.length() > 768) return null;
        try {
            URI uri = new URI(text);
            if (!"navatv".equals(uri.getScheme()) || !"connect".equals(uri.getRawAuthority())
                    || !"".equals(uri.getRawPath()) || uri.getRawFragment() != null
                    || uri.getRawQuery() == null) return null;
            Map<String, String> fields = new HashMap<>();
            for (String item : uri.getRawQuery().split("&", -1)) {
                int split = item.indexOf('=');
                if (split < 1) return null;
                String key = item.substring(0, split);
                if (!key.matches("v|host|port|lan|name")) return null;
                String value = URLDecoder.decode(item.substring(split + 1), "UTF-8");
                if (fields.put(key, value) != null) return null;
            }
            String host = fields.get("host");
            String lan = fields.get("lan");
            String portText = fields.get("port");
            if (!"1".equals(fields.get("v")) || !privateIpv4(host)
                    || (lan != null && !privateIpv4(lan)) || portText == null
                    || !portText.matches("[1-9][0-9]{0,4}")) return null;
            int port = Integer.parseInt(portText);
            if (port > 65535) return null;
            String name = fields.getOrDefault("name", "TV").trim();
            if (name.isEmpty()) name = "TV";
            if (name.length() > 80 || name.matches("(?s).*[\\p{C}\\p{Zl}\\p{Zp}].*")) return null;
            return new TvInvite(host, host.equals(lan) ? null : lan, name, port);
        } catch (java.net.URISyntaxException | IllegalArgumentException
                | java.io.UnsupportedEncodingException invalid) {
            return null;
        }
    }

    private static boolean privateIpv4(String address) {
        if (address == null) return false;
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] bytes = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
            bytes[i] = Integer.parseInt(parts[i]);
            if (bytes[i] > 255) return false;
        }
        return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31)
                || (bytes[0] == 192 && bytes[1] == 168)
                || (bytes[0] == 100 && bytes[1] >= 64 && bytes[1] <= 127);
    }

}

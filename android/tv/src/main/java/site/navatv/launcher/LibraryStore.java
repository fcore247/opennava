package site.navatv.launcher;

import android.content.Context;
import android.os.Bundle;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Rust launcher keeps the viewer's libraries in libraries.json and reports its open page in screen.json.
 * The authenticated companion (or the viewer's own agent over adb) reads and changes them here.
 */
final class LibraryStore {
    private static final int ENCODING = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    private static String read(Context context, String name) {
        try (InputStream in = new FileInputStream(new File(context.getFilesDir(), name));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException missing) { return ""; }
    }

    private static boolean write(Context context, List<OwnLibrary.Source> sources) {
        File target = new File(context.getFilesDir(), "libraries.json");
        File temporary = new File(context.getFilesDir(), "libraries.json.tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(OwnLibrary.writeSources(sources).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException failed) { return false; }
        return temporary.renameTo(target);
    }

    static String saved(Context context) { return read(context, "libraries.json"); }

    static boolean store(Context context, List<OwnLibrary.Source> sources) { return write(context, sources); }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), ENCODING);
    }

    static boolean handles(String method) {
        return method != null && (method.startsWith("library_source") || method.equals("screen_state"));
    }

    static synchronized Bundle companion(Context context, String method, String arg) {
        Bundle out = new Bundle();
        if ("screen_state".equals(method)) {
            out.putString("rust64", encode(read(context, "screen.json")));
            return out;
        }
        List<OwnLibrary.Source> sources = OwnLibrary.parseSources(read(context, "libraries.json"));
        if ("library_sources_get".equals(method)) {
            out.putString("sources64", encode(OwnLibrary.writeSources(sources)));
            return out;
        }
        boolean handled = false;
        if ("library_source_remove".equals(method) && arg != null) {
            List<OwnLibrary.Source> kept = new ArrayList<>(sources);
            for (OwnLibrary.Source source : sources) if (source.id.equals(arg)) kept.remove(source);
            handled = kept.size() < sources.size() && write(context, kept);
        } else if ("library_source_put".equals(method) && arg != null && arg.length() <= 120_000) {
            try {
                List<OwnLibrary.Source> added = OwnLibrary.parseSources(
                        "[" + new String(Base64.decode(arg, ENCODING), StandardCharsets.UTF_8) + "]");
                if (added.size() == 1) {
                    List<OwnLibrary.Source> next = new ArrayList<>(sources);
                    int existing = -1;
                    for (int i = 0; i < next.size(); i++) if (next.get(i).id.equals(added.get(0).id)) existing = i;
                    if (existing >= 0) next.set(existing, added.get(0));
                    else if (next.size() < OwnLibrary.LIBRARY_LIMIT) next.add(added.get(0));
                    else return result(out, false);
                    handled = write(context, next);
                    if (handled) out.putString("id", added.get(0).id);
                }
            } catch (IllegalArgumentException invalid) { handled = false; }
        }
        return result(out, handled);
    }

    private static Bundle result(Bundle out, boolean handled) {
        out.putBoolean("handled", handled);
        return out;
    }

    private LibraryStore() {}
}

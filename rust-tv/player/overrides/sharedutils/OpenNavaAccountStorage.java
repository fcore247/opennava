package com.liskovsoft.sharedutils.prefs;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Protects the preview SDK's persisted account data with a key local to this Android app. */
final class OpenNavaAccountStorage {
    private static final String ALIAS = "opennava.youtube.tv.v1";
    private static final String PREFIX = "v1:";

    static synchronized String read(GlobalPreferences prefs, String name) {
        String stored = prefs.getData(name);
        if (stored == null || stored.isEmpty()) return null;
        try {
            if (!stored.startsWith(PREFIX)) {
                // The first native preview with Google pairing already uses this store.
                // Also migrate private data if a developer ran an earlier SDK build.
                write(prefs, name, stored);
                return stored;
            }
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            // A missing Keystore key requires fresh Google consent.
            return null;
        }
    }

    static synchronized void write(GlobalPreferences prefs, String name, String data) {
        if (data == null || data.isEmpty()) {
            // The pinned SDK skips null writes. An empty file erases the previous grant.
            prefs.setData(name, "");
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            prefs.setData(name, PREFIX + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP));
        } catch (Exception unavailable) {
            throw new IllegalStateException("YouTube account storage is unavailable.");
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}

package site.navatv.companion;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Date;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** ADB requires raw RSA for legacy authentication and TLS. Keep that identity encrypted at rest. */
final class AdbIdentity {
    private static final String ALIAS = "fernsehen.adb.wrap.v1";
    final PrivateKey key;
    final X509Certificate certificate;

    private AdbIdentity(PrivateKey key, X509Certificate certificate) {
        this.key = key;
        this.certificate = certificate;
    }

    static synchronized AdbIdentity load(Context context) throws Exception {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "adb-identity-v1"));
        KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
        keystore.load(null);
        SecretKey wrapper = (SecretKey) keystore.getKey(ALIAS, null);
        if (wrapper == null) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            wrapper = generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        if (file.getBaseFile().exists()) {
            byte[] contents = file.readFully();
            if (contents.length < 30 || contents.length > 32768) throw new IOException("The local TV key is damaged.");
            cipher.init(Cipher.DECRYPT_MODE, wrapper, new GCMParameterSpec(128, contents, 0, 12));
            byte[] decoded = cipher.doFinal(contents, 12, contents.length - 12);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(decoded))) {
                int keyLength = input.readUnsignedShort();
                if (keyLength >= decoded.length - 2) throw new IOException("Invalid TV key.");
                byte[] privateData = new byte[keyLength];
                try {
                    input.readFully(privateData);
                    PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateData));
                    X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
                    return new AdbIdentity(key, cert);
                } finally { Arrays.fill(privateData, (byte) 0); }
            } finally { Arrays.fill(decoded, (byte) 0); }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X509Certificate cert = certificate(pair);
        cipher.init(Cipher.ENCRYPT_MODE, wrapper);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream encoded = new DataOutputStream(bytes)) {
            byte[] privateData = pair.getPrivate().getEncoded();
            try {
                encoded.writeShort(privateData.length);
                encoded.write(privateData);
            } finally { Arrays.fill(privateData, (byte) 0); }
            encoded.write(cert.getEncoded());
        }
        byte[] payload = bytes.toByteArray();
        FileOutputStream out = file.startWrite();
        try {
            out.write(cipher.getIV());
            out.write(cipher.doFinal(payload));
            file.finishWrite(out);
        } catch (Exception e) {
            file.failWrite(out);
            throw e;
        } finally { Arrays.fill(payload, (byte) 0); }
        return new AdbIdentity(pair.getPrivate(), cert);
    }

    private static X509Certificate certificate(KeyPair pair) throws Exception {
        X500Name name = new X500Name("CN=OpenNava Companion");
        AlgorithmIdentifier algorithm = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
        long now = System.currentTimeMillis();
        V3TBSCertificateGenerator builder = new V3TBSCertificateGenerator();
        builder.setSerialNumber(new ASN1Integer(new BigInteger(126, new SecureRandom()).add(BigInteger.ONE)));
        builder.setIssuer(name);
        builder.setSubject(name);
        builder.setStartDate(new Time(new Date(now - 86400000L)));
        builder.setEndDate(new Time(new Date(now + 20 * 365L * 86400000L)));
        builder.setSignature(algorithm);
        builder.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
        TBSCertificate tbs = builder.generateTBSCertificate();
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(tbs.getEncoded());
        ASN1EncodableVector parts = new ASN1EncodableVector();
        parts.add(tbs);
        parts.add(algorithm);
        parts.add(new DERBitString(signature.sign()));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(new DERSequence(parts).getEncoded()));
    }
}

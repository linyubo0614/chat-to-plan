package cn.chatplanner.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AppSettings {
  private static final String ALIAS = "chatplanner.deepseek.api.v1";
  private static final Object KEY_LOCK = new Object();
  private final SharedPreferences prefs;

  AppSettings(Context context) {
    prefs =
        context
            .getApplicationContext()
            .getSharedPreferences("deepseek_settings", Context.MODE_PRIVATE);
  }

  synchronized String apiKey() {
    String ciphertext = prefs.getString("api_key", "");
    if (ciphertext.isEmpty()) return "";
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          encryptionKey(),
          new GCMParameterSpec(128, Base64.decode(prefs.getString("api_iv", ""), Base64.NO_WRAP)));
      return new String(
          cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("API Key 无法读取，请在设置中重新保存。", e);
    }
  }

  synchronized void setApiKey(String value) {
    String clean = value == null ? "" : value.trim();
    if (clean.isEmpty()) {
      if (!prefs.edit().remove("api_key").remove("api_iv").commit())
        throw new IllegalStateException("无法保存设置。");
      return;
    }
    if (!clean.matches("[!-~]{1,512}")) throw new IllegalArgumentException("API Key 格式不正确。");
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
      byte[] ciphertext = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
      if (!prefs
          .edit()
          .putString("api_key", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
          .putString("api_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
          .commit()) throw new IllegalStateException("无法保存设置。");
    } catch (Exception e) {
      throw new IllegalStateException("API Key 无法保存，请重试。", e);
    }
  }

  String model() {
    return prefs.getString("model", "deepseek-flash");
  }

  void setModel(String value) {
    String clean = value == null ? "" : value.trim();
    if (!clean.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}"))
      throw new IllegalArgumentException("模型名称格式不正确。");
    if (!prefs.edit().putString("model", clean).commit())
      throw new IllegalStateException("无法保存模型设置。");
  }

  private SecretKey encryptionKey() throws Exception {
    synchronized (KEY_LOCK) {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      if (!store.containsAlias(ALIAS)) {
        KeyGenerator generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(
            new KeyGenParameterSpec.Builder(
                    ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        generator.generateKey();
      }
      return (SecretKey) store.getKey(ALIAS, null);
    }
  }
}

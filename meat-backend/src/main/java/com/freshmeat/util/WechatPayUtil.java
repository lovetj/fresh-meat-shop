package com.freshmeat.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信支付 V3 工具类
 * 提供：商户私钥/公钥加载、V3 请求头签名、小程序调起支付签名、回调报文 AES-GCM 解密
 */
@Slf4j
public class WechatPayUtil {

    private static final String SCHEMA = "WECHATPAY2-SHA256-RSA2048";
    private static final Map<String, PrivateKey> PRIVATE_KEY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, PublicKey> PUBLIC_KEY_CACHE = new ConcurrentHashMap<>();

    /**
     * 加载商户 API 私钥
     */
    public static PrivateKey loadPrivateKey(String privateKeyPath) throws Exception {
        if (!StringUtils.hasText(privateKeyPath)) {
            throw new IllegalArgumentException("微信支付私钥路径 privateKeyPath 不能为空");
        }
        PrivateKey cached = PRIVATE_KEY_CACHE.get(privateKeyPath);
        if (cached != null) {
            return cached;
        }
        String keyStr = readCertContent(privateKeyPath, "私钥");
        String cleanKey = keyStr
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] decoded = Base64.getDecoder().decode(cleanKey);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        PRIVATE_KEY_CACHE.put(privateKeyPath, privateKey);
        return privateKey;
    }

    /**
     * 加载平台公钥/证书
     */
    public static PublicKey loadPublicKey(String publicKeyPath) throws Exception {
        if (!StringUtils.hasText(publicKeyPath)) {
            throw new IllegalArgumentException("平台公钥路径 publicKeyPath 不能为空");
        }
        PublicKey cached = PUBLIC_KEY_CACHE.get(publicKeyPath);
        if (cached != null) {
            return cached;
        }
        String keyStr = readCertContent(publicKeyPath, "平台公钥");
        String cleanKey = keyStr
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s+", "");

        byte[] decoded = Base64.getDecoder().decode(cleanKey);
        PublicKey publicKey;
        try {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
            publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);
        } catch (Exception e) {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.Certificate cert = cf.generateCertificate(new java.io.ByteArrayInputStream(keyStr.getBytes(StandardCharsets.UTF_8)));
            publicKey = cert.getPublicKey();
        }

        PUBLIC_KEY_CACHE.put(publicKeyPath, publicKey);
        return publicKey;
    }

    private static String readCertContent(String path, String desc) throws Exception {
        byte[] bytes;
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length()).trim();
            if (cp.startsWith("/")) {
                cp = cp.substring(1);
            }
            ClassPathResource res = new ClassPathResource(cp);
            if (!res.exists()) {
                throw new IllegalStateException("classpath 下未找到" + desc + "文件: " + cp);
            }
            try (InputStream in = res.getInputStream()) {
                bytes = FileCopyUtils.copyToByteArray(in);
            }
        } else {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) {
                throw new IllegalStateException("磁盘路径未找到" + desc + "文件: " + path);
            }
            try (InputStream in = new java.io.FileInputStream(file)) {
                bytes = FileCopyUtils.copyToByteArray(in);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 对消息字符串使用 SHA256withRSA 进行签名
     */
    public static String signSha256WithRsa(String message, PrivateKey privateKey) throws Exception {
        Signature sign = Signature.getInstance("SHA256withRSA");
        sign.initSign(privateKey);
        sign.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sign.sign());
    }

    /**
     * 生成微信支付 V3 请求 Authorization 认证头部
     */
    public static String buildAuthorizationHeader(String method, String canonicalUrl, String body,
                                                  String mchid, String certSerialNo, PrivateKey privateKey) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String message = method + "\n"
                + canonicalUrl + "\n"
                + timestamp + "\n"
                + nonceStr + "\n"
                + (body != null ? body : "") + "\n";

        String signature = signSha256WithRsa(message, privateKey);
        return SCHEMA + " "
                + "mchid=\"" + mchid + "\","
                + "nonce_str=\"" + nonceStr + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + certSerialNo + "\","
                + "signature=\"" + signature + "\"";
    }

    /**
     * 构建微信小程序调起支付签名 (wx.requestPayment)
     * 待签名字符串格式：
     * appId\n
     * timeStamp\n
     * nonceStr\n
     * package\n
     */
    public static String buildMiniappPaySign(String appId, String timeStamp, String nonceStr,
                                             String packageVal, PrivateKey privateKey) throws Exception {
        String message = appId + "\n"
                + timeStamp + "\n"
                + nonceStr + "\n"
                + packageVal + "\n";
        return signSha256WithRsa(message, privateKey);
    }

    /**
     * 微信支付 V3 异步回调报文解密 (AEAD_AES_256_GCM)
     *
     * @param associatedData 附加数据
     * @param nonce          随机串
     * @param ciphertext     密文
     * @param apiV3Key       商户平台 APIv3 密钥 (32字节)
     * @return 解密后的 JSON 明文字符串
     */
    public static String decryptToString(String associatedData, String nonce, String ciphertext, String apiV3Key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        if (StringUtils.hasText(associatedData)) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}

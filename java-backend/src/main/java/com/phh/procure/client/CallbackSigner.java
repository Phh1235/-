package com.phh.procure.client;

import com.phh.procure.common.BizException;
import com.phh.procure.common.ErrorCode;
import com.phh.procure.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 回调签名校验：HMAC-SHA256(secret, timestamp + "\n" + nonce + "\n" + rawBody)。
 * <p>为什么签名要覆盖 timestamp 和 nonce：只签 body 的话，攻击者可以重放旧请求；
 * 只签 body+timestamp 的话，同一秒内可重放。三者一起签 + 服务端记录 nonce 才能防重放。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackSigner {

    /** 允许的时钟偏移，超过即认为过期请求。 */
    private static final long MAX_SKEW_SECONDS = 300;

    private final AppProperties props;

    public void verify(String rawBody, String timestamp, String nonce, String signature) {
        String secret = props.getAgent().getCallbackSecret();
        if (secret == null || secret.isBlank()) {
            throw new BizException(ErrorCode.INTERNAL, "回调密钥未配置");
        }
        if (timestamp == null || nonce == null || signature == null) {
            throw new BizException(ErrorCode.AGENT_CALLBACK_INVALID, "回调签名头缺失");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.AGENT_CALLBACK_INVALID, "回调时间戳格式错误");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > MAX_SKEW_SECONDS) {
            throw new BizException(ErrorCode.AGENT_CALLBACK_INVALID, "回调时间戳超出允许范围（±5 分钟）");
        }
        String expected = sign(secret, timestamp, nonce, rawBody == null ? "" : rawBody);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ErrorCode.AGENT_CALLBACK_INVALID, "回调签名不匹配");
        }
    }

    public static String sign(String secret, String timestamp, String nonce, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "\n" + nonce + "\n" + rawBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}

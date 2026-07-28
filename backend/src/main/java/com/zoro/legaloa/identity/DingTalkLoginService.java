package com.zoro.legaloa.identity;

import com.zoro.legaloa.common.BusinessException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DingTalkLoginService {
    private static final String STATE_PREFIX = "lawoa:oauth-state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private final SecureRandom secureRandom = new SecureRandom();
    private final StringRedisTemplate redisTemplate;
    private final String authMode;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String organizationId;

    public DingTalkLoginService(
            StringRedisTemplate redisTemplate,
            @Value("${app.auth.mode}") String authMode,
            @Value("${app.dingtalk.client-id}") String clientId,
            @Value("${app.dingtalk.client-secret}") String clientSecret,
            @Value("${app.dingtalk.redirect-uri:}") String redirectUri,
            @Value("${app.tenant.organization-id:}") String organizationId
    ) {
        this.redisTemplate = redisTemplate;
        this.authMode = authMode;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.organizationId = organizationId;
    }

    public LoginConfiguration configuration() {
        return new LoginConfiguration(authMode, isConfigured(), STATE_TTL.toSeconds());
    }

    public AuthorizationResponse begin() {
        requireConfigured();
        byte[] random = new byte[24];
        secureRandom.nextBytes(random);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        UUID tenantId = configuredOrganizationId();
        redisTemplate.opsForValue().set(STATE_PREFIX + state, tenantId.toString(), STATE_TTL);
        String authorizationUrl = "https://login.dingtalk.com/oauth2/auth"
                + "?redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&client_id=" + encode(clientId)
                + "&scope=openid"
                + "&state=" + encode(state)
                + "&prompt=consent";
        return new AuthorizationResponse(authorizationUrl, STATE_TTL.toSeconds());
    }

    public UUID consumeState(String state) {
        if (state == null || state.isBlank() || state.length() > 128) {
            throw invalidState();
        }
        String tenantId = redisTemplate.opsForValue().getAndDelete(STATE_PREFIX + state);
        if (tenantId == null) {
            throw invalidState();
        }
        try {
            return UUID.fromString(tenantId);
        } catch (IllegalArgumentException exception) {
            throw invalidState();
        }
    }

    private boolean isConfigured() {
        return !clientId.isBlank()
                && !clientSecret.isBlank()
                && !redirectUri.isBlank()
                && validOrganizationId();
    }

    private UUID configuredOrganizationId() {
        try {
            return UUID.fromString(organizationId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    "TENANT_NOT_CONFIGURED",
                    "尚未配置登录所属组织",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private boolean validOrganizationId() {
        try {
            UUID.fromString(organizationId);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException(
                    "DINGTALK_NOT_CONFIGURED",
                    "尚未配置钉钉应用凭证和登录回调地址",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private static BusinessException invalidState() {
        return new BusinessException(
                "OAUTH_STATE_INVALID",
                "登录请求已失效，请重新发起钉钉登录",
                HttpStatus.UNAUTHORIZED
        );
    }

    public record LoginConfiguration(
            String authMode,
            boolean dingtalkConfigured,
            long authorizationStateTtlSeconds
    ) {}

    public record AuthorizationResponse(String authorizationUrl, long expiresIn) {}
}

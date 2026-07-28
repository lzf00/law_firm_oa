package com.zoro.legaloa.identity;

import com.zoro.legaloa.common.BusinessException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class DingTalkAuthClient {
    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public DingTalkAuthClient(
            RestClient.Builder builder,
            @Value("${app.dingtalk.client-id}") String clientId,
            @Value("${app.dingtalk.client-secret}") String clientSecret
    ) {
        this.restClient = builder.baseUrl("https://api.dingtalk.com").build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public DingTalkIdentity exchange(String authorizationCode) {
        requireConfigured();
        try {
            TokenResponse token = restClient.post()
                    .uri("/v1.0/oauth2/userAccessToken")
                    .body(Map.of(
                            "clientId", clientId,
                            "clientSecret", clientSecret,
                            "code", authorizationCode,
                            "grantType", "authorization_code"
                    ))
                    .retrieve()
                    .body(TokenResponse.class);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw new BusinessException(
                        "DINGTALK_TOKEN_INVALID", "钉钉未返回有效用户令牌", HttpStatus.BAD_GATEWAY
                );
            }
            MeResponse me = restClient.get()
                    .uri("/v1.0/contact/users/me")
                    .header("x-acs-dingtalk-access-token", token.accessToken())
                    .retrieve()
                    .body(MeResponse.class);
            if (me == null) {
                throw new BusinessException(
                        "DINGTALK_USER_INVALID", "无法读取钉钉用户身份", HttpStatus.BAD_GATEWAY
                );
            }
            String externalId = firstNonBlank(me.unionId(), me.openId());
            return new DingTalkIdentity(externalId, me.nick(), me.mobile());
        } catch (RestClientResponseException exception) {
            throw new BusinessException(
                    "DINGTALK_AUTH_FAILED",
                    "钉钉身份校验失败，请重新进入应用",
                    HttpStatus.UNAUTHORIZED
            );
        }
    }

    private void requireConfigured() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new BusinessException(
                    "DINGTALK_NOT_CONFIGURED",
                    "尚未配置钉钉企业内部应用凭证",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        throw new BusinessException(
                "DINGTALK_ID_MISSING", "钉钉身份缺少可关联标识", HttpStatus.BAD_GATEWAY
        );
    }

    record TokenResponse(String accessToken, long expireIn) {}
    record MeResponse(String nick, String unionId, String openId, String mobile, String stateCode) {}
    public record DingTalkIdentity(String externalId, String nickname, String mobile) {}
}


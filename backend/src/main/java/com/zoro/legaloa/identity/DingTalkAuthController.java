package com.zoro.legaloa.identity;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/dingtalk")
public class DingTalkAuthController {
    private final DingTalkAuthClient dingTalkAuthClient;
    private final SessionTokenService sessionTokenService;
    private final JdbcClient jdbcClient;
    private final AuditService auditService;
    private final DingTalkLoginService dingTalkLoginService;

    public DingTalkAuthController(
            DingTalkAuthClient dingTalkAuthClient,
            SessionTokenService sessionTokenService,
            JdbcClient jdbcClient,
            AuditService auditService,
            DingTalkLoginService dingTalkLoginService
    ) {
        this.dingTalkAuthClient = dingTalkAuthClient;
        this.sessionTokenService = sessionTokenService;
        this.jdbcClient = jdbcClient;
        this.auditService = auditService;
        this.dingTalkLoginService = dingTalkLoginService;
    }

    @PostMapping("/exchange")
    @Transactional
    ExchangeResponse exchange(@Valid @RequestBody ExchangeRequest request) {
        dingTalkLoginService.consumeState(request.state());
        DingTalkAuthClient.DingTalkIdentity identity = dingTalkAuthClient.exchange(request.authorizationCode());
        Map<String, Object> user = jdbcClient.sql("""
                        SELECT id, organization_id, username, display_name
                        FROM users
                        WHERE dingtalk_user_id = :externalId
                          AND status = 'ACTIVE' AND deleted_at IS NULL
                        """)
                .param("externalId", identity.externalId())
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "DINGTALK_USER_NOT_SYNCED",
                        "该钉钉账号尚未同步到本系统，请联系管理员",
                        HttpStatus.FORBIDDEN
                ));
        RequestActor actor = new RequestActor(
                (UUID) user.get("id"),
                (UUID) user.get("organization_id"),
                (String) user.get("username"),
                (String) user.get("display_name")
        );
        String token = sessionTokenService.issue(actor.username());
        auditService.success(actor, "DINGTALK_LOGIN", "USER", actor.userId());
        return new ExchangeResponse(token, 8 * 60 * 60, actor.displayName());
    }

    public record ExchangeRequest(
            @NotBlank @Size(max = 2048) String authorizationCode,
            @NotBlank @Size(max = 128) String state
    ) {}

    public record ExchangeResponse(String accessToken, int expiresIn, String displayName) {}
}

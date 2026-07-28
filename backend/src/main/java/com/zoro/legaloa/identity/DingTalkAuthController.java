package com.zoro.legaloa.identity;

import com.zoro.legaloa.common.AuditService;
import com.zoro.legaloa.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
    private final AuditService auditService;
    private final DingTalkLoginService dingTalkLoginService;
    private final BrowserSessionCookieService cookieService;
    private final IdentityDirectory identityDirectory;

    public DingTalkAuthController(
            DingTalkAuthClient dingTalkAuthClient,
            SessionTokenService sessionTokenService,
            AuditService auditService,
            DingTalkLoginService dingTalkLoginService,
            BrowserSessionCookieService cookieService,
            IdentityDirectory identityDirectory
    ) {
        this.dingTalkAuthClient = dingTalkAuthClient;
        this.sessionTokenService = sessionTokenService;
        this.auditService = auditService;
        this.dingTalkLoginService = dingTalkLoginService;
        this.cookieService = cookieService;
        this.identityDirectory = identityDirectory;
    }

    @PostMapping("/exchange")
    @Transactional
    ExchangeResponse exchange(
            @Valid @RequestBody ExchangeRequest request,
            HttpServletResponse response
    ) {
        UUID organizationId = dingTalkLoginService.consumeState(request.state());
        DingTalkAuthClient.DingTalkIdentity identity = dingTalkAuthClient.exchange(request.authorizationCode());
        RequestActor actor = identityDirectory
                .findActiveByDingTalkId(identity.externalId(), organizationId)
                .orElseThrow(() -> new BusinessException(
                        "DINGTALK_USER_NOT_SYNCED",
                        "该钉钉账号尚未同步到本系统，请联系管理员",
                        HttpStatus.FORBIDDEN
                ));
        String token = sessionTokenService.issue(actor);
        response.addHeader(
                "Set-Cookie",
                cookieService.create(token, SessionTokenService.SESSION_TTL)
        );
        auditService.success(actor, "DINGTALK_LOGIN", "USER", actor.userId());
        return new ExchangeResponse(8 * 60 * 60, actor.displayName());
    }

    public record ExchangeRequest(
            @NotBlank @Size(max = 2048) String authorizationCode,
            @NotBlank @Size(max = 128) String state
    ) {}

    public record ExchangeResponse(int expiresIn, String displayName) {}
}

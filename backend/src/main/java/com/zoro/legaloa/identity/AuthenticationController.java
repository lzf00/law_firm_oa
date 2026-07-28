package com.zoro.legaloa.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final DingTalkLoginService dingTalkLoginService;
    private final SessionTokenService sessionTokenService;

    public AuthenticationController(
            DingTalkLoginService dingTalkLoginService,
            SessionTokenService sessionTokenService
    ) {
        this.dingTalkLoginService = dingTalkLoginService;
        this.sessionTokenService = sessionTokenService;
    }

    @GetMapping("/config")
    DingTalkLoginService.LoginConfiguration configuration() {
        return dingTalkLoginService.configuration();
    }

    @PostMapping("/dingtalk/begin")
    DingTalkLoginService.AuthorizationResponse beginDingTalkLogin() {
        return dingTalkLoginService.begin();
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            sessionTokenService.revoke(authorization.substring(7));
        }
    }
}

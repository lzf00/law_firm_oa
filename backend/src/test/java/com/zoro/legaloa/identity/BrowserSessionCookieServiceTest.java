package com.zoro.legaloa.identity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BrowserSessionCookieServiceTest {
    @Test
    void createsSecureHttpOnlySameSiteCookieWithoutExposingTokenToJavascript() {
        BrowserSessionCookieService service = new BrowserSessionCookieService(true);

        String cookie = service.create("opaque-token", Duration.ofHours(8));

        assertThat(cookie)
                .contains("LAW_OA_SESSION=opaque-token")
                .contains("Path=/")
                .contains("Max-Age=28800")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    void readsOnlyTheNamedCookieAndClearsItWithZeroAge() {
        BrowserSessionCookieService service = new BrowserSessionCookieService(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "ignored"), new Cookie("LAW_OA_SESSION", "session"));

        assertThat(service.read(request)).contains("session");
        assertThat(service.clear())
                .contains("LAW_OA_SESSION=")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .doesNotContain("Secure");
    }
}

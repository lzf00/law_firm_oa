package com.zoro.legaloa.party;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoro.legaloa.identity.BrowserSessionCookieService;
import com.zoro.legaloa.identity.SessionTokenService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ConflictCheckController.class)
class ConflictCheckControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ConflictCheckService conflictCheckService;

    @MockitoBean
    SessionTokenService sessionTokenService;

    @MockitoBean
    BrowserSessionCookieService browserSessionCookieService;

    @Test
    @WithMockUser
    void rejectsEmptyMatterTitleAndPartyList() throws Exception {
        mockMvc.perform(post("/api/conflict-checks/preview")
                        .with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new Request("", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.matterTitle").exists())
                .andExpect(jsonPath("$.fieldErrors.partyIds").exists());

        verifyNoInteractions(conflictCheckService);
    }

    record Request(String matterTitle, List<String> partyIds) {}
}

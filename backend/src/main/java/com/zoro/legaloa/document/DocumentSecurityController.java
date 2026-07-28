package com.zoro.legaloa.document;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/document-security")
public class DocumentSecurityController {
    private final DocumentSecurityService securityService;

    public DocumentSecurityController(DocumentSecurityService securityService) {
        this.securityService = securityService;
    }

    @GetMapping("/events")
    List<DocumentSecurityService.SecurityEventView> events(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return securityService.events(status, limit);
    }
}

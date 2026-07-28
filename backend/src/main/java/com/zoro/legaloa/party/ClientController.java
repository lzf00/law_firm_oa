package com.zoro.legaloa.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clients")
public class ClientController {
    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    @GetMapping
    List<ClientView> list() {
        return clientService.list();
    }

    @PostMapping
    ClientView create(@Valid @RequestBody CreateClientRequest request) {
        return clientService.create(request);
    }

    public record CreateClientRequest(
            @NotNull UUID partyId,
            @NotBlank @Size(max = 60) String clientNumber,
            UUID ownerUserId,
            @Size(max = 100) String source
    ) {}

    public record ClientView(
            UUID id,
            UUID partyId,
            String clientNumber,
            String displayName,
            String partyType,
            UUID ownerUserId,
            String ownerName,
            String status
    ) {}
}


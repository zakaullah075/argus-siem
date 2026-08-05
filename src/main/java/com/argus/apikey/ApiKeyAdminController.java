package com.argus.apikey;

import com.argus.apikey.dto.ApiKeyResponse;
import com.argus.apikey.dto.IssueApiKeyRequest;
import com.argus.apikey.dto.IssuedApiKeyResponse;
import com.argus.audit.AuditService;
import com.argus.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/management/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyService apiKeyService;
    private final AuditService auditService;

    public ApiKeyAdminController(ApiKeyService apiKeyService, AuditService auditService) {
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
    }

    /**
     * The plaintext key appears in this response and nowhere else, ever. Only
     * its hash is stored, so it cannot be shown again or recovered.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedApiKeyResponse issue(@Valid @RequestBody IssueApiKeyRequest request) {
        UUID tenantId = AuthenticatedUser.tenantId();

        String plaintext = apiKeyService.issue(tenantId, request.name());

        // The key value is deliberately absent from the audit record.
        auditService.record(tenantId, AuthenticatedUser.userId(), "apikey.issued", request.name());

        return new IssuedApiKeyResponse(plaintext,
                "Store this now. It cannot be retrieved again.");
    }

    @GetMapping
    public List<ApiKeyResponse> list() {
        return apiKeyService.listForTenant(AuthenticatedUser.tenantId());
    }

    @DeleteMapping("/{keyId}")
    public void revoke(@PathVariable UUID keyId) {
        UUID tenantId = AuthenticatedUser.tenantId();
        apiKeyService.revoke(tenantId, keyId);
        auditService.record(tenantId, AuthenticatedUser.userId(), "apikey.revoked", keyId.toString());
    }
}

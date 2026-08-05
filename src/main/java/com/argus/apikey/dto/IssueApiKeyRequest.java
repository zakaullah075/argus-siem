package com.argus.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IssueApiKeyRequest(
        @NotBlank @Size(max = 120) String name
) {
}

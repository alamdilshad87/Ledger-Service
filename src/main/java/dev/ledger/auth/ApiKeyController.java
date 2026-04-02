package dev.ledger.auth;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/api-keys")
public class ApiKeyController {

    private final ApiKeyService keys;

    public ApiKeyController(ApiKeyService keys) {
        this.keys = keys;
    }

    /** The full key is in this response only; store it now. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyService.CreatedKey create(@Valid @RequestBody CreateKeyRequest request) {
        return keys.create(request.name().trim(), request.role());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        keys.revoke(id);
    }

    public record CreateKeyRequest(@NotBlank @Size(max = 100) String name, @NotNull ApiClient.Role role) {
    }
}

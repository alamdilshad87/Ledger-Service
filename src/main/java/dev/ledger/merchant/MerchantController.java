package dev.ledger.merchant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/merchants")
public class MerchantController {

    private final MerchantService merchants;

    public MerchantController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantService.CreatedMerchant create(@Valid @RequestBody CreateMerchantRequest request) {
        return merchants.create(request.name());
    }

    public record CreateMerchantRequest(@NotBlank @Size(max = 100) String name) {
    }
}

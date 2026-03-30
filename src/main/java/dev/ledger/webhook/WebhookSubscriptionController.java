package dev.ledger.webhook;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/webhooks")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService subscriptions;

    public WebhookSubscriptionController(WebhookSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookSubscriptionService.CreatedSubscription create(@Valid @RequestBody SubscribeRequest request) {
        return subscriptions.subscribe(request.merchantId(), request.url());
    }

    public record SubscribeRequest(@NotNull UUID merchantId, @NotBlank @Size(max = 2048) String url) {
    }
}

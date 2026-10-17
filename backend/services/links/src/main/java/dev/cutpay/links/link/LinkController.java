package dev.cutpay.links.link;

import dev.cutpay.links.link.LinkDtos.CreateLinkRequest;
import dev.cutpay.links.link.LinkDtos.LinkList;
import dev.cutpay.links.link.LinkDtos.LinkPaymentList;
import dev.cutpay.links.link.LinkDtos.LinkView;
import dev.cutpay.links.link.LinkDtos.UpdateLinkRequest;
import dev.cutpay.web.RequestMode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The merchant's side: create and manage links. An API key sees only links in its own mode. */
@RestController
class LinkController {

    private final LinkService links;

    LinkController(LinkService links) {
        this.links = links;
    }

    @PostMapping("/v1/payment_links")
    @ResponseStatus(HttpStatus.CREATED)
    LinkView create(@AuthenticationPrincipal Jwt jwt,
                    @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                    @Valid @RequestBody CreateLinkRequest request) {
        return links.create(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), request);
    }

    @GetMapping("/v1/payment_links")
    LinkList list(@AuthenticationPrincipal Jwt jwt,
                  @RequestParam(name = "livemode", defaultValue = "false") boolean requestedLivemode,
                  @RequestParam(required = false) String status,
                  @RequestParam(name = "starting_after", required = false) String startingAfter,
                  @RequestParam(defaultValue = "20") int limit) {
        return links.list(merchant(jwt), RequestMode.resolve(claim(jwt), requestedLivemode), status, startingAfter,
                limit);
    }

    @GetMapping("/v1/payment_links/{id}")
    LinkView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return links.get(merchant(jwt), claim(jwt), id);
    }

    @PatchMapping("/v1/payment_links/{id}")
    LinkView update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                    @Valid @RequestBody UpdateLinkRequest request) {
        return links.update(merchant(jwt), claim(jwt), id, request);
    }

    @GetMapping("/v1/payment_links/{id}/payments")
    LinkPaymentList payments(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return links.payments(merchant(jwt), claim(jwt), id);
    }

    static String merchant(Jwt jwt) {
        return jwt.getClaimAsString("mid");
    }

    /** The mode an API key is limited to, or null for a dashboard session (which can open either by id). */
    static Boolean claim(Jwt jwt) {
        return jwt.getClaimAsBoolean("livemode");
    }
}

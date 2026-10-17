package dev.cutpay.links.link;

import dev.cutpay.links.link.LinkDtos.PublicLinkView;
import dev.cutpay.links.link.LinkDtos.SessionCreated;
import dev.cutpay.links.link.LinkDtos.SessionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated: the unguessable slug is the credential, like the client secret of a hosted checkout. The
 * gateway rate-limits both routes per client address (docs/adr/0014-links-qr-and-virtual-pos.md).
 */
@RestController
class PublicLinkController {

    private final LinkService links;

    PublicLinkController(LinkService links) {
        this.links = links;
    }

    @GetMapping("/links/{slug}")
    PublicLinkView get(@PathVariable String slug) {
        return links.publicView(slug);
    }

    @PostMapping("/links/{slug}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionCreated session(@PathVariable String slug, @Valid @RequestBody SessionRequest request) {
        return links.createSession(slug, request);
    }
}

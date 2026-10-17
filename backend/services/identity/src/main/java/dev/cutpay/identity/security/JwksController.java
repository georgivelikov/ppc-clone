package dev.cutpay.identity.security;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing key so other services (the gateway first) can verify access tokens. */
@RestController
public class JwksController {

    private final SigningKeyService keys;

    public JwksController(SigningKeyService keys) {
        this.keys = keys;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String jwks() {
        return keys.publicJwksJson();
    }
}

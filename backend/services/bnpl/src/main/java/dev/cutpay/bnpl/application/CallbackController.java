package dev.cutpay.bnpl.application;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where providers report decisions they did not make at once. Anyone can post here, so nothing in the body is believed
 * until the provider adapter has checked it (for Lendo, a signature over the exact body).
 */
@RestController
class CallbackController {

    private final ApplicationService service;

    CallbackController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping("/bnpl/callback/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void receive(@PathVariable String provider, @RequestBody String body, HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name)));
        service.onCallback(provider, headers, body);
    }
}

package dev.cutpay.reporting.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Back-office tool for records the services could not process. Reading is for any staff, acting is for admins. */
@RestController
class DeadLetterController {

    record Target(@NotBlank String topic, int partition, long offset) {
    }

    private final DeadLetters deadLetters;

    DeadLetterController(DeadLetters deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping("/v1/staff/dead_letters")
    Map<String, Object> list(@AuthenticationPrincipal Jwt jwt) {
        StaffGuard.staff(jwt);
        return Map.of("object", "list", "data", deadLetters.waiting());
    }

    @PostMapping("/v1/staff/dead_letters/redrive")
    DeadLetters.Letter redrive(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Target target) {
        StaffGuard.admin(jwt);
        return deadLetters.redrive(target.topic(), target.partition(), target.offset(), jwt.getClaimAsString("email"));
    }

    @PostMapping("/v1/staff/dead_letters/discard")
    Map<String, String> discard(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Target target) {
        StaffGuard.admin(jwt);
        deadLetters.discard(target.topic(), target.partition(), target.offset(), jwt.getClaimAsString("email"));
        return Map.of("status", "discarded");
    }
}

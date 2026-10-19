package dev.cutpay.identity.auth;

import dev.cutpay.identity.domain.Membership;
import dev.cutpay.identity.domain.Merchant;
import dev.cutpay.identity.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128, message = "Password must be at least 10 characters") String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(max = 160) String businessName,
            @NotBlank @Size(min = 2, max = 2) String country) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            String merchantId,
            String totpCode) {
    }

    public record UserView(String id, String email, String fullName, boolean emailVerified, boolean totpEnabled) {

        static UserView of(User user) {
            return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getEmailVerifiedAt() != null,
                    user.isTotpEnabled());
        }
    }

    public record MerchantView(String id, String displayName, String legalName, String country,
                               String defaultCurrency, String status) {

        public static MerchantView of(Merchant m) {
            return new MerchantView(m.getId(), m.getDisplayName(), m.getLegalName(), m.getCountry(),
                    m.getDefaultCurrency(), m.getStatus().name().toLowerCase(Locale.ROOT));
        }
    }

    public record MembershipView(String merchantId, String merchantName, String role) {

        static MembershipView of(Membership membership, Merchant merchant) {
            return new MembershipView(merchant.getId(), merchant.getDisplayName(),
                    membership.getRole().name().toLowerCase(Locale.ROOT));
        }
    }

    public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserView user,
                               MerchantView merchant, String role) {
    }

    public record MeResponse(UserView user, java.util.List<MembershipView> memberships) {
    }
}

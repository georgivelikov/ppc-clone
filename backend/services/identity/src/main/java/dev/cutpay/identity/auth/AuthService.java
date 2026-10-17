package dev.cutpay.identity.auth;

import dev.cutpay.events.OutboxWriter;
import dev.cutpay.identity.auth.AuthDtos.AuthResponse;
import dev.cutpay.identity.auth.AuthDtos.LoginRequest;
import dev.cutpay.identity.auth.AuthDtos.MembershipView;
import dev.cutpay.identity.auth.AuthDtos.MerchantView;
import dev.cutpay.identity.auth.AuthDtos.MeResponse;
import dev.cutpay.identity.auth.AuthDtos.SignupRequest;
import dev.cutpay.identity.auth.AuthDtos.UserView;
import dev.cutpay.identity.domain.Membership;
import dev.cutpay.identity.domain.Merchant;
import dev.cutpay.identity.domain.Repositories.MembershipRepository;
import dev.cutpay.identity.domain.Repositories.MerchantRepository;
import dev.cutpay.identity.domain.Repositories.UserRepository;
import dev.cutpay.identity.domain.User;
import dev.cutpay.identity.security.JwtService;
import dev.cutpay.money.SupportedCurrencies;
import dev.cutpay.web.ApiException;
import dev.cutpay.web.Ids;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public static final String MERCHANT_TOPIC = "merchant.events";

    /** Verified against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    private final UserRepository users;
    private final MerchantRepository merchants;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final OutboxWriter outbox;

    public AuthService(UserRepository users, MerchantRepository merchants, MembershipRepository memberships,
                       PasswordEncoder passwordEncoder, JwtService jwt, RefreshTokenService refreshTokens,
                       OutboxWriter outbox) {
        this.users = users;
        this.merchants = merchants;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.outbox = outbox;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /** The access token response plus the raw refresh token destined for the cookie. */
    public record Session(AuthResponse response, String refreshToken) {
    }

    @Transactional
    public Session signup(SignupRequest request) {
        String country = request.country().toUpperCase(Locale.ROOT);
        if (!SupportedCurrencies.isSupportedCountry(country)) {
            throw ApiException.invalidRequest("unsupported_country", "Country is not supported yet", "country");
        }
        if (users.existsByEmail(request.email())) {
            throw ApiException.conflict("email_taken", "An account with this email already exists", "email");
        }

        Merchant merchant = merchants.save(new Merchant(Ids.generate("mer"), request.businessName().trim(), country,
                SupportedCurrencies.defaultFor(country)));
        User user = users.save(new User(Ids.generate("usr"), request.email().trim(),
                passwordEncoder.encode(request.password()), request.fullName().trim()));
        Membership membership = memberships.save(new Membership(Ids.generate("mbr"), user.getId(), merchant.getId(),
                Membership.Role.OWNER));
        user.recordLogin();

        outbox.write(MERCHANT_TOPIC, "MerchantCreated", merchant.getId(), merchant.getId(), null, Map.of(
                "merchant_id", merchant.getId(),
                "legal_name", merchant.getLegalName(),
                "country", merchant.getCountry(),
                "default_currency", merchant.getDefaultCurrency(),
                "owner_user_id", user.getId()));

        return startSession(user, merchant, membership);
    }

    @Transactional
    public Session login(LoginRequest request) {
        User user = users.findByEmail(request.email()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean passwordOk = passwordEncoder.matches(request.password(), hash);
        if (user == null || !passwordOk) {
            throw ApiException.unauthorized("invalid_credentials", "Incorrect email or password");
        }

        List<Membership> all = memberships.findByUserIdOrderByCreatedAtAsc(user.getId());
        Membership membership = request.merchantId() == null
                ? all.stream().findFirst().orElseThrow(() -> ApiException.forbidden("no_merchant",
                        "This account is not a member of any business"))
                : all.stream().filter(m -> m.getMerchantId().equals(request.merchantId())).findFirst()
                        .orElseThrow(() -> ApiException.forbidden("not_a_member", "You are not a member of that business"));
        user.recordLogin();
        Merchant merchant = merchants.findById(membership.getMerchantId()).orElseThrow();
        return startSession(user, merchant, membership);
    }

    // noRollbackFor: on token reuse, consume() revokes all of the user's sessions and we then reject the
    // request with a 401. Rolling back on that exception would silently undo the revocation.
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawRefreshToken) {
        var token = refreshTokens.consume(rawRefreshToken)
                .orElseThrow(() -> ApiException.unauthorized("invalid_refresh_token", "Please sign in again"));
        User user = users.findById(token.getUserId()).orElseThrow();
        Membership membership = memberships.findByUserIdAndMerchantId(user.getId(), token.getMerchantId())
                .orElseThrow(() -> ApiException.unauthorized("invalid_refresh_token", "Please sign in again"));
        Merchant merchant = merchants.findById(membership.getMerchantId()).orElseThrow();
        return startSession(user, merchant, membership);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    @Transactional(readOnly = true)
    public MeResponse me(String userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("unknown_user", "Unknown user"));
        List<MembershipView> views = memberships.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(m -> MembershipView.of(m, merchants.findById(m.getMerchantId()).orElseThrow()))
                .toList();
        return new MeResponse(UserView.of(user), views);
    }

    private Session startSession(User user, Merchant merchant, Membership membership) {
        String role = membership.getRole().name().toLowerCase(Locale.ROOT);
        var token = jwt.issue(user.getId(), user.getEmail(), merchant.getId(), role);
        String refresh = refreshTokens.create(user.getId(), merchant.getId());
        var response = new AuthResponse(token.value(), "Bearer", token.expiresInSeconds(), UserView.of(user),
                MerchantView.of(merchant), role);
        return new Session(response, refresh);
    }
}

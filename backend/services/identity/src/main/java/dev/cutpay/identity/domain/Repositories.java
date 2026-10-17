package dev.cutpay.identity.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class Repositories {

    private Repositories() {
    }

    public interface MerchantRepository extends JpaRepository<Merchant, String> {
    }

    public interface UserRepository extends JpaRepository<User, String> {

        @Query("select u from User u where lower(u.email) = lower(:email)")
        Optional<User> findByEmail(@Param("email") String email);

        @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
        boolean existsByEmail(@Param("email") String email);
    }

    public interface MembershipRepository extends JpaRepository<Membership, String> {

        List<Membership> findByUserIdOrderByCreatedAtAsc(String userId);

        Optional<Membership> findByUserIdAndMerchantId(String userId, String merchantId);
    }

    public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

        Optional<RefreshToken> findByTokenHash(String tokenHash);

        @Modifying
        @Query("update RefreshToken t set t.revokedAt = current_timestamp where t.userId = :userId and t.revokedAt is null")
        int revokeAllForUser(@Param("userId") String userId);
    }
}

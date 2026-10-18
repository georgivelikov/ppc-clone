package dev.cutpay.identity.connected;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, String> {

    @Query("""
            select a from ConnectedAccount a
            where a.platformMerchantId = :platform and a.livemode = :livemode
              and (:after is null or a.id < :after)
            order by a.id desc
            """)
    List<ConnectedAccount> page(@Param("platform") String platform, @Param("livemode") boolean livemode,
                                @Param("after") String after, Pageable pageable);
}

package dev.cutpay.payments.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class Repositories {

    private Repositories() {
    }

    public record VolumeRow(String currency, long count, long volume) {
    }

    public interface PaymentIntentRepository
            extends JpaRepository<PaymentIntent, String>, JpaSpecificationExecutor<PaymentIntent> {

        Optional<PaymentIntent> findByClientSecret(String clientSecret);

        Optional<PaymentIntent> findByIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select p from PaymentIntent p where p.id = :id and p.merchantId = :merchantId "
                + "and p.livemode = :livemode")
        Optional<PaymentIntent> lockByIdAndMerchantIdAndLivemode(@Param("id") String id,
                                                                 @Param("merchantId") String merchantId,
                                                                 @Param("livemode") boolean livemode);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select p from PaymentIntent p where p.id = :id")
        Optional<PaymentIntent> lockById(@Param("id") String id);

        @Query("""
                select new dev.cutpay.payments.domain.Repositories$VolumeRow(p.currency, count(p), sum(p.amount))
                from PaymentIntent p
                where p.merchantId = :merchantId and p.livemode = :livemode and p.createdAt >= :since
                  and p.status in (dev.cutpay.payments.domain.PaymentStatus.SUCCEEDED,
                                   dev.cutpay.payments.domain.PaymentStatus.PARTIALLY_REFUNDED,
                                   dev.cutpay.payments.domain.PaymentStatus.REFUNDED)
                group by p.currency
                """)
        List<VolumeRow> volumeSince(@Param("merchantId") String merchantId, @Param("livemode") boolean livemode,
                                    @Param("since") Instant since);
    }

    public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, String> {

        List<PaymentAttempt> findByPaymentIntentIdOrderByCreatedAtAsc(String paymentIntentId);

        Optional<PaymentAttempt> findFirstByAcquirerRef(String acquirerRef);

        Optional<PaymentAttempt> findFirstByPaymentIntentIdOrderByCreatedAtDesc(String paymentIntentId);
    }

    public interface RefundRepository extends JpaRepository<Refund, String> {

        List<Refund> findByPaymentIntentIdOrderByCreatedAtAsc(String paymentIntentId);

        @Query("""
                select r from Refund r, PaymentIntent p
                where p.id = r.paymentIntentId
                  and r.merchantId = :merchantId and p.livemode = :livemode
                  and (:paymentId is null or r.paymentIntentId = :paymentId)
                  and (:startingAfter is null or r.id < :startingAfter)
                order by r.id desc
                """)
        List<Refund> page(@Param("merchantId") String merchantId, @Param("livemode") boolean livemode,
                          @Param("paymentId") String paymentId,
                          @Param("startingAfter") String startingAfter, Pageable pageable);

        @Query("select coalesce(sum(r.amount), 0) from Refund r where r.paymentIntentId = :paymentId "
                + "and r.status = 'succeeded'")
        long succeededTotal(@Param("paymentId") String paymentId);
    }
}

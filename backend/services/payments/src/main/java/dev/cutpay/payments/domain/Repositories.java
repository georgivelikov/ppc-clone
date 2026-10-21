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

        Optional<PaymentIntent> findByIdAndMerchantId(String id, String merchantId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select p from PaymentIntent p where p.id = :id and p.merchantId = :merchantId")
        Optional<PaymentIntent> lockByIdAndMerchantId(@Param("id") String id, @Param("merchantId") String merchantId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select p from PaymentIntent p where p.id = :id and p.merchantId = :merchantId "
                + "and p.livemode = :livemode")
        Optional<PaymentIntent> lockByIdAndMerchantIdAndLivemode(@Param("id") String id,
                                                                 @Param("merchantId") String merchantId,
                                                                 @Param("livemode") boolean livemode);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select p from PaymentIntent p where p.id = :id")
        Optional<PaymentIntent> lockById(@Param("id") String id);

        /** Authorisations whose capture window has closed, oldest first. */
        @Query("select p.id from PaymentIntent p where p.status = dev.cutpay.payments.domain.PaymentStatus.REQUIRES_CAPTURE "
                + "and p.captureBefore <= :now order by p.captureBefore")
        List<String> findIdsDueForExpiry(@Param("now") Instant now, Pageable pageable);

        /** Payments that started processing before {@code before} and never got an answer recorded. */
        @Query("select p.id from PaymentIntent p where p.status = dev.cutpay.payments.domain.PaymentStatus.PROCESSING "
                + "and p.updatedAt < :before order by p.updatedAt")
        List<String> findIdsStuckProcessing(@Param("before") Instant before, Pageable pageable);

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

    public interface CustomerRepository extends JpaRepository<Customer, String> {

        Optional<Customer> findByIdAndMerchantIdAndDeletedAtIsNull(String id, String merchantId);

        @Query("""
                select c from Customer c
                where c.merchantId = :merchantId and c.livemode = :livemode and c.deletedAt is null
                  and (:startingAfter is null or c.id < :startingAfter)
                  and (:q is null or lower(coalesce(c.email, '')) like :q or lower(coalesce(c.name, '')) like :q
                       or lower(c.id) like :q)
                order by c.id desc
                """)
        List<Customer> page(@Param("merchantId") String merchantId, @Param("livemode") boolean livemode,
                            @Param("startingAfter") String startingAfter, @Param("q") String like, Pageable pageable);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select c from Customer c where c.id = :id")
        Optional<Customer> lockById(@Param("id") String id);
    }

    public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, String> {

        @Query("select m from PaymentMethod m where m.customerId = :customerId and m.detachedAt is null order by m.id desc")
        List<PaymentMethod> attached(@Param("customerId") String customerId);

        Optional<PaymentMethod> findByIdAndCustomerId(String id, String customerId);

        @Query("select m from PaymentMethod m where m.customerId = :customerId and m.detachedAt is null "
                + "and m.cardFingerprint = :fingerprint and m.cardExpMonth = :month and m.cardExpYear = :year")
        List<PaymentMethod> sameCard(@Param("customerId") String customerId, @Param("fingerprint") String fingerprint,
                                     @Param("month") int month, @Param("year") int year);
    }

    public interface SetupIntentRepository extends JpaRepository<SetupIntent, String> {

        Optional<SetupIntent> findByClientSecret(String clientSecret);

        Optional<SetupIntent> findByIdAndMerchantId(String id, String merchantId);

        Optional<SetupIntent> findFirstByAcquirerRef(String acquirerRef);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select s from SetupIntent s where s.id = :id")
        Optional<SetupIntent> lockById(@Param("id") String id);
    }

    public interface PaymentTransferRepository extends JpaRepository<PaymentTransfer, String> {

        List<PaymentTransfer> findByPaymentIntentIdOrderByIdAsc(String paymentIntentId);
    }
}

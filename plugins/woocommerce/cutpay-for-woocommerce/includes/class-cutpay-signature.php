<?php
/**
 * Verifies the Cutpay-Signature header of a webhook.
 *
 * The header looks like  t=1760000000,v1=<hex>  where v1 is HMAC-SHA256(secret, t . "." . body). The timestamp is
 * signed with the body, so a captured request cannot be replayed later: anything outside the tolerance is refused.
 * Several v1 values may be present (a secret being rotated); one match is enough. Mirrors WebhookSignature.java.
 */

defined( 'ABSPATH' ) || exit;

class Cutpay_Signature {

	const VALID                = 'valid';
	const MALFORMED_HEADER     = 'malformed_header';
	const TIMESTAMP_OUT_OF_RANGE = 'timestamp_out_of_range';
	const NO_MATCHING_SIGNATURE  = 'no_matching_signature';

	/** Builds a header value; used by tests and by the "send test event" tooling. */
	public static function sign( $secret, $body, $timestamp ) {
		return 't=' . $timestamp . ',v1=' . hash_hmac( 'sha256', $timestamp . '.' . $body, $secret );
	}

	/**
	 * @param string[]    $secrets   current secret(s) to try
	 * @param string      $body      the raw request body, exactly as received
	 * @param string|null $header    the Cutpay-Signature header
	 * @param int         $now       seconds since the epoch
	 * @param int         $tolerance seconds
	 */
	public static function verify( array $secrets, $body, $header, $now, $tolerance = 300 ) {
		if ( ! is_string( $header ) || '' === $header || ! is_string( $body ) ) {
			return self::MALFORMED_HEADER;
		}
		$timestamp  = null;
		$signatures = array();
		foreach ( explode( ',', $header ) as $part ) {
			$eq = strpos( $part, '=' );
			if ( false === $eq || 0 === $eq ) {
				return self::MALFORMED_HEADER;
			}
			$key   = trim( substr( $part, 0, $eq ) );
			$value = trim( substr( $part, $eq + 1 ) );
			if ( 't' === $key ) {
				if ( ! ctype_digit( $value ) ) {
					return self::MALFORMED_HEADER;
				}
				$timestamp = (int) $value;
			} elseif ( 'v1' === $key ) {
				$signatures[] = $value;
			}
		}
		if ( null === $timestamp || empty( $signatures ) ) {
			return self::MALFORMED_HEADER;
		}
		if ( abs( $now - $timestamp ) > $tolerance ) {
			return self::TIMESTAMP_OUT_OF_RANGE;
		}
		foreach ( $secrets as $secret ) {
			if ( '' === $secret ) {
				continue;
			}
			$expected = hash_hmac( 'sha256', $timestamp . '.' . $body, $secret );
			foreach ( $signatures as $candidate ) {
				if ( hash_equals( $expected, $candidate ) ) {
					return self::VALID;
				}
			}
		}
		return self::NO_MATCHING_SIGNATURE;
	}
}

<?php
/**
 * Tests for the parts of the plugin that need nothing from WordPress. No framework:
 *   docker run --rm -v "$PWD/plugins/woocommerce/cutpay-for-woocommerce:/p" php:8.3-cli php /p/tests/run.php
 */

define( 'ABSPATH', __DIR__ );
require __DIR__ . '/../includes/class-cutpay-signature.php';
require __DIR__ . '/../includes/class-cutpay-money.php';

$failures = 0;
$count    = 0;
function check( $name, $condition ) {
	global $failures, $count;
	$count++;
	if ( ! $condition ) {
		$failures++;
		echo "FAIL  $name\n";
	}
}

// --- signature ---------------------------------------------------------------------------------------------------
$secret = 'whsec_test';
$body   = '{"id":"evt_1","type":"payment_intent.succeeded"}';
$now    = 1760000000;

$header = Cutpay_Signature::sign( $secret, $body, $now );
check( 'a signature made now verifies', Cutpay_Signature::VALID === Cutpay_Signature::verify( array( $secret ), $body, $header, $now ) );
check( 'within the tolerance', Cutpay_Signature::VALID === Cutpay_Signature::verify( array( $secret ), $body, $header, $now + 299 ) );
check( 'a replay later is refused', Cutpay_Signature::TIMESTAMP_OUT_OF_RANGE === Cutpay_Signature::verify( array( $secret ), $body, $header, $now + 301 ) );
check( 'a body changed by one byte is refused', Cutpay_Signature::NO_MATCHING_SIGNATURE === Cutpay_Signature::verify( array( $secret ), $body . ' ', $header, $now ) );
check( 'another secret is refused', Cutpay_Signature::NO_MATCHING_SIGNATURE === Cutpay_Signature::verify( array( 'whsec_other' ), $body, $header, $now ) );
check( 'either of two secrets will do', Cutpay_Signature::VALID === Cutpay_Signature::verify( array( 'whsec_other', $secret ), $body, $header, $now ) );
check( 'an empty secret never matches', Cutpay_Signature::NO_MATCHING_SIGNATURE === Cutpay_Signature::verify( array( '' ), $body, Cutpay_Signature::sign( '', $body, $now ), $now ) );
check( 'no header', Cutpay_Signature::MALFORMED_HEADER === Cutpay_Signature::verify( array( $secret ), $body, '', $now ) );
check( 'garbage header', Cutpay_Signature::MALFORMED_HEADER === Cutpay_Signature::verify( array( $secret ), $body, 'nonsense', $now ) );
check( 'no v1', Cutpay_Signature::MALFORMED_HEADER === Cutpay_Signature::verify( array( $secret ), $body, "t=$now", $now ) );
// A header can carry several signatures while a secret is rotated: one good one is enough.
$rotating = 't=' . $now . ',v1=' . str_repeat( '0', 64 ) . ',v1=' . hash_hmac( 'sha256', $now . '.' . $body, $secret );
check( 'one good signature among several', Cutpay_Signature::VALID === Cutpay_Signature::verify( array( $secret ), $body, $rotating, $now ) );
// The timestamp is part of what is signed, so it cannot be moved to dodge the tolerance.
$moved = 't=' . ( $now + 1000 ) . ',v1=' . explode( 'v1=', $header )[1];
check( 'a moved timestamp does not verify', Cutpay_Signature::VALID !== Cutpay_Signature::verify( array( $secret ), $body, $moved, $now + 1000 ) );

// --- money -------------------------------------------------------------------------------------------------------
check( '12.34 is 1234', 1234 === Cutpay_Money::to_minor( '12.34' ) );
check( 'float noise does not lose a cent: 19.99', 1999 === Cutpay_Money::to_minor( 19.99 ) );
check( 'float noise does not lose a cent: 0.29', 29 === Cutpay_Money::to_minor( 0.29 ) );
check( 'float noise does not lose a cent: 1.15', 115 === Cutpay_Money::to_minor( 1.15 ) );
check( 'whole numbers', 5000 === Cutpay_Money::to_minor( '50' ) );
check( 'a rounding case rounds once: 10.005', 1001 === Cutpay_Money::to_minor( '10.005' ) || 1000 === Cutpay_Money::to_minor( '10.005' ) );
check( 'back again', '12.34' === Cutpay_Money::from_minor( 1234 ) );
check( 'back again, small', '0.05' === Cutpay_Money::from_minor( 5 ) );
check( 'EUR is supported', Cutpay_Money::supports( 'eur' ) );
check( 'JPY is not', ! Cutpay_Money::supports( 'JPY' ) );

echo "$count checks, $failures failed\n";
exit( $failures ? 1 : 0 );

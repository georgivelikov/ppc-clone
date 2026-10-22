<?php
/**
 * Amounts. Cutpay takes whole minor units (cents) as integers; WooCommerce holds decimals as strings or floats.
 * Floating point must not decide how much a customer pays, so conversion goes through a string and rounds once.
 */

defined( 'ABSPATH' ) || exit;

class Cutpay_Money {

	/** Currencies Cutpay accepts, all with two decimals. */
	const CURRENCIES = array( 'EUR', 'RON', 'PLN', 'HUF', 'CZK', 'GBP', 'USD', 'CHF' );

	/** "12.34" (or 12.34) to 1234. */
	public static function to_minor( $amount ) {
		return (int) round( (float) number_format( (float) $amount, 2, '.', '' ) * 100 );
	}

	/** 1234 to "12.34". */
	public static function from_minor( $minor ) {
		return number_format( ( (int) $minor ) / 100, 2, '.', '' );
	}

	public static function supports( $currency ) {
		return in_array( strtoupper( (string) $currency ), self::CURRENCIES, true );
	}
}

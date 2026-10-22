<?php
/**
 * A small client for the Cutpay API: create a hosted checkout session, read a payment, refund one.
 * Every call is made from the server with the secret key; the key never reaches the browser.
 */

defined( 'ABSPATH' ) || exit;

class Cutpay_Api {

	/** @var string */
	private $base_url;
	/** @var string */
	private $secret_key;

	public function __construct( $base_url, $secret_key ) {
		$this->base_url   = untrailingslashit( $base_url );
		$this->secret_key = $secret_key;
	}

	/** @return array|WP_Error the created session: payment_id, client_secret, url */
	public function create_checkout_session( array $payload, $idempotency_key ) {
		return $this->request( 'POST', '/v1/checkout_sessions', $payload, $idempotency_key );
	}

	/** @return array|WP_Error */
	public function get_payment( $payment_id ) {
		return $this->request( 'GET', '/v1/payment_intents/' . rawurlencode( $payment_id ) );
	}

	/** @return array|WP_Error */
	public function refund( $payment_id, $amount_minor, $reason, $idempotency_key ) {
		$body = array(
			'payment_intent' => $payment_id,
			'amount'         => $amount_minor,
		);
		if ( $reason ) {
			$body['reason'] = mb_substr( $reason, 0, 200 );
		}
		return $this->request( 'POST', '/v1/refunds', $body, $idempotency_key );
	}

	private function request( $method, $path, $body = null, $idempotency_key = null ) {
		$headers = array(
			'Authorization' => 'Bearer ' . $this->secret_key,
			'Content-Type'  => 'application/json',
			'User-Agent'    => 'cutpay-for-woocommerce/' . CUTPAY_WC_VERSION . '; WordPress/' . get_bloginfo( 'version' ),
		);
		if ( $idempotency_key ) {
			// A retry after a timeout must not create a second payment or refund.
			$headers['Idempotency-Key'] = $idempotency_key;
		}
		$args = array(
			'method'  => $method,
			'headers' => $headers,
			'timeout' => 20,
		);
		if ( null !== $body ) {
			$args['body'] = wp_json_encode( $body );
		}
		$response = wp_remote_request( $this->base_url . $path, $args );
		if ( is_wp_error( $response ) ) {
			return $response;
		}
		$status  = wp_remote_retrieve_response_code( $response );
		$decoded = json_decode( wp_remote_retrieve_body( $response ), true );
		if ( $status < 200 || $status >= 300 ) {
			$message = is_array( $decoded ) && isset( $decoded['error']['message'] ) ? $decoded['error']['message'] : 'HTTP ' . $status;
			return new WP_Error( 'cutpay_http_' . $status, $message );
		}
		return is_array( $decoded ) ? $decoded : array();
	}
}

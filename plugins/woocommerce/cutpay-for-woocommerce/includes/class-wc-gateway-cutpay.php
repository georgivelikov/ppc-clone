<?php
/**
 * The Cutpay payment gateway for WooCommerce.
 *
 * Flow: the customer chooses Cutpay and places the order; we create a hosted checkout session and send them there
 * (card details never touch this site). They come back to the order page, but the order is marked paid only by what
 * Cutpay tells us over a signed channel: the webhook, or, on return, a direct read of the payment with our secret key.
 * The browser being sent back proves nothing.
 */

defined( 'ABSPATH' ) || exit;

class WC_Gateway_Cutpay extends WC_Payment_Gateway {

	const META_PAYMENT_ID = '_cutpay_payment_id';
	const META_EVENTS     = '_cutpay_events';

	public function __construct() {
		$this->id                 = 'cutpay';
		$this->method_title       = __( 'Cutpay', 'cutpay-for-woocommerce' );
		$this->method_description = __( 'Pay with a card or other methods on Cutpay\'s hosted checkout.', 'cutpay-for-woocommerce' );
		$this->has_fields         = false;
		$this->supports           = array( 'products', 'refunds' );

		$this->init_form_fields();
		$this->init_settings();

		$this->title       = $this->get_option( 'title' );
		$this->description = $this->get_option( 'description' );
		$this->enabled     = $this->get_option( 'enabled' );

		add_action( 'woocommerce_update_options_payment_gateways_' . $this->id, array( $this, 'process_admin_options' ) );
		add_action( 'woocommerce_api_wc_gateway_cutpay', array( $this, 'handle_webhook' ) );
		add_action( 'woocommerce_thankyou_' . $this->id, array( $this, 'confirm_on_return' ) );
	}

	public function init_form_fields() {
		$this->form_fields = array(
			'enabled'            => array(
				'title'   => __( 'Enable', 'cutpay-for-woocommerce' ),
				'type'    => 'checkbox',
				'label'   => __( 'Enable Cutpay', 'cutpay-for-woocommerce' ),
				'default' => 'no',
			),
			'title'              => array(
				'title'   => __( 'Title', 'cutpay-for-woocommerce' ),
				'type'    => 'text',
				'default' => __( 'Pay by card', 'cutpay-for-woocommerce' ),
			),
			'description'        => array(
				'title'   => __( 'Description', 'cutpay-for-woocommerce' ),
				'type'    => 'textarea',
				'default' => __( 'You will be taken to a secure page to pay.', 'cutpay-for-woocommerce' ),
			),
			'testmode'           => array(
				'title'       => __( 'Test mode', 'cutpay-for-woocommerce' ),
				'type'        => 'checkbox',
				'label'       => __( 'Use test keys (no real money)', 'cutpay-for-woocommerce' ),
				'default'     => 'yes',
				'description' => __( 'Test and live are separate worlds: keys, payments and webhook secrets do not cross over.', 'cutpay-for-woocommerce' ),
			),
			'api_url'            => array(
				'title'       => __( 'API URL', 'cutpay-for-woocommerce' ),
				'type'        => 'text',
				'default'     => 'http://localhost:8080',
				'description' => __( 'The Cutpay API (the gateway), without a trailing slash.', 'cutpay-for-woocommerce' ),
			),
			'test_secret_key'    => array(
				'title' => __( 'Test secret key', 'cutpay-for-woocommerce' ),
				'type'  => 'password',
			),
			'test_webhook_secret' => array(
				'title'       => __( 'Test webhook secret', 'cutpay-for-woocommerce' ),
				'type'        => 'password',
				'description' => sprintf(
					/* translators: %s: webhook URL */
					__( 'Create a webhook endpoint in the Cutpay dashboard with this URL and paste its secret here: %s', 'cutpay-for-woocommerce' ),
					esc_html( WC()->api_request_url( 'wc_gateway_cutpay' ) )
				),
			),
			'live_secret_key'    => array(
				'title' => __( 'Live secret key', 'cutpay-for-woocommerce' ),
				'type'  => 'password',
			),
			'live_webhook_secret' => array(
				'title' => __( 'Live webhook secret', 'cutpay-for-woocommerce' ),
				'type'  => 'password',
			),
		);
	}

	public function is_available() {
		if ( ! parent::is_available() ) {
			return false;
		}
		// Offering a method that cannot be completed is worse than not offering it.
		if ( '' === $this->secret_key() ) {
			return false;
		}
		return Cutpay_Money::supports( get_woocommerce_currency() );
	}

	private function testmode() {
		return 'yes' === $this->get_option( 'testmode' );
	}

	private function secret_key() {
		return trim( (string) $this->get_option( $this->testmode() ? 'test_secret_key' : 'live_secret_key' ) );
	}

	private function api() {
		return new Cutpay_Api( $this->get_option( 'api_url' ), $this->secret_key() );
	}

	/** Called when the customer places the order. */
	public function process_payment( $order_id ) {
		$order = wc_get_order( $order_id );
		if ( ! $order ) {
			return array( 'result' => 'failure' );
		}
		$amount = Cutpay_Money::to_minor( $order->get_total() );
		$locale = substr( get_locale(), 0, 2 );

		$payload = array(
			'amount'         => $amount,
			'currency'       => strtoupper( $order->get_currency() ),
			'description'    => sprintf( /* translators: 1: order number 2: site name */ __( 'Order %1$s at %2$s', 'cutpay-for-woocommerce' ), $order->get_order_number(), get_bloginfo( 'name' ) ),
			'customer_email' => $order->get_billing_email(),
			'merchant_name'  => get_bloginfo( 'name' ),
			'return_url'     => $this->get_return_url( $order ),
			'metadata'       => array(
				'woocommerce_order_id'  => (string) $order->get_id(),
				'woocommerce_order_key' => $order->get_order_key(),
			),
		);
		if ( in_array( $locale, array( 'en', 'bg', 'ro', 'pl', 'hu', 'el' ), true ) ) {
			$payload['locale'] = $locale;
		}

		// The same order and amount give the same key, so a double click or a retry returns the same session.
		$session = $this->api()->create_checkout_session( $payload, 'wc-' . $order->get_id() . '-' . $amount . '-' . $payload['currency'] );
		if ( is_wp_error( $session ) || empty( $session['url'] ) || empty( $session['payment_id'] ) ) {
			$message = is_wp_error( $session ) ? $session->get_error_message() : 'no checkout URL in the response';
			$order->add_order_note( sprintf( 'Cutpay could not start the payment: %s', $message ) );
			wc_add_notice( __( 'We could not start the payment. Please try again or choose another method.', 'cutpay-for-woocommerce' ), 'error' );
			return array( 'result' => 'failure' );
		}

		$order->update_meta_data( self::META_PAYMENT_ID, $session['payment_id'] );
		$order->update_status( 'on-hold', __( 'Waiting for the customer to pay on Cutpay.', 'cutpay-for-woocommerce' ) );
		$order->save();
		WC()->cart->empty_cart();

		return array(
			'result'   => 'success',
			'redirect' => $session['url'],
		);
	}

	/** Refund from the WooCommerce order screen. */
	public function process_refund( $order_id, $amount = null, $reason = '' ) {
		$order      = wc_get_order( $order_id );
		$payment_id = $order ? $order->get_meta( self::META_PAYMENT_ID ) : '';
		if ( ! $order || '' === $payment_id ) {
			return new WP_Error( 'cutpay_no_payment', __( 'This order has no Cutpay payment.', 'cutpay-for-woocommerce' ) );
		}
		$minor = Cutpay_Money::to_minor( null === $amount ? $order->get_total() : $amount );
		if ( $minor < 1 ) {
			return new WP_Error( 'cutpay_bad_amount', __( 'The refund amount must be positive.', 'cutpay-for-woocommerce' ) );
		}
		// Keyed by order, amount and the number of refunds so far: a retry of this refund is the same request,
		// the next partial refund is a different one.
		$key    = 'wc-refund-' . $order->get_id() . '-' . $minor . '-' . count( $order->get_refunds() );
		$result = $this->api()->refund( $payment_id, $minor, $reason, $key );
		if ( is_wp_error( $result ) ) {
			return $result;
		}
		$order->add_order_note( sprintf( 'Refunded %s through Cutpay (%s).', Cutpay_Money::from_minor( $minor ), isset( $result['id'] ) ? $result['id'] : 'ok' ) );
		return true;
	}

	/** The signed notification from Cutpay: the one place an order becomes paid. */
	public function handle_webhook() {
		$body   = file_get_contents( 'php://input' );
		$header = isset( $_SERVER['HTTP_CUTPAY_SIGNATURE'] ) ? sanitize_text_field( wp_unslash( $_SERVER['HTTP_CUTPAY_SIGNATURE'] ) ) : '';

		$secrets = array_filter(
			array(
				trim( (string) $this->get_option( 'test_webhook_secret' ) ),
				trim( (string) $this->get_option( 'live_webhook_secret' ) ),
			)
		);
		$result = Cutpay_Signature::verify( $secrets, $body, $header, time() );
		if ( Cutpay_Signature::VALID !== $result ) {
			status_header( 400 );
			echo esc_html( 'invalid signature: ' . $result );
			exit;
		}

		$event = json_decode( $body, true );
		if ( ! is_array( $event ) || empty( $event['id'] ) || empty( $event['type'] ) ) {
			status_header( 400 );
			exit;
		}
		$payment = isset( $event['data']['object'] ) ? $event['data']['object'] : array();
		$order   = $this->find_order( $payment );
		if ( $order ) {
			$this->apply_event( $order, $event, $payment );
		}
		// Anything we do not act on is still acknowledged, or Cutpay would keep retrying it.
		status_header( 200 );
		echo 'ok';
		exit;
	}

	private function find_order( array $payment ) {
		// The event names the payment; the order is the one we stored that payment id on when we created it.
		// (Looking an order up by a number in the event would let anyone who can create a payment name any order.)
		if ( empty( $payment['id'] ) ) {
			return false;
		}
		$orders = wc_get_orders(
			array(
				'limit'          => 1,
				'payment_method' => $this->id,
				'meta_key'       => self::META_PAYMENT_ID, // phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_key
				'meta_value'     => $payment['id'], // phpcs:ignore WordPress.DB.SlowDBQuery.slow_db_query_meta_value
			)
		);
		return empty( $orders ) ? false : $orders[0];
	}

	private function apply_event( WC_Order $order, array $event, array $payment ) {
		// Delivery is at least once: a repeated event must change nothing.
		$seen = (array) $order->get_meta( self::META_EVENTS );
		if ( in_array( $event['id'], $seen, true ) ) {
			return;
		}
		$seen[] = $event['id'];
		$order->update_meta_data( self::META_EVENTS, array_slice( $seen, -50 ) );

		if ( 'payment_intent.succeeded' === $event['type'] ) {
			$this->mark_paid( $order, $payment );
		} elseif ( 'payment_intent.payment_failed' === $event['type'] && $order->has_status( array( 'pending', 'on-hold' ) ) ) {
			$code = isset( $payment['failure_code'] ) ? $payment['failure_code'] : 'payment_failed';
			$order->update_status( 'failed', sprintf( 'Cutpay: payment failed (%s).', $code ) );
		}
		$order->save();
	}

	/** Never trust an event for more than it proves: the amount and currency must be what this order was priced at. */
	private function mark_paid( WC_Order $order, array $payment ) {
		if ( ! $order->has_status( array( 'pending', 'on-hold', 'failed' ) ) ) {
			return; // already paid, cancelled or refunded
		}
		$expected = Cutpay_Money::to_minor( $order->get_total() );
		$amount   = isset( $payment['amount'] ) ? (int) $payment['amount'] : -1;
		$currency = isset( $payment['currency'] ) ? strtoupper( $payment['currency'] ) : '';
		if ( $amount !== $expected || strtoupper( $order->get_currency() ) !== $currency ) {
			$order->update_status( 'on-hold', sprintf( 'Cutpay reports %s %s paid but the order is %s %s. Check before shipping.', Cutpay_Money::from_minor( max( $amount, 0 ) ), $currency, $order->get_total(), $order->get_currency() ) );
			return;
		}
		$order->payment_complete( $payment['id'] );
		$order->add_order_note( sprintf( 'Cutpay payment %s succeeded.', $payment['id'] ) );
	}

	/**
	 * The customer is back on the order page. The webhook may not have arrived yet, so ask Cutpay directly (server to
	 * server, with the secret key) instead of showing "waiting" until it does. Same checks as the webhook.
	 */
	public function confirm_on_return( $order_id ) {
		$order = wc_get_order( $order_id );
		if ( ! $order || ! $order->has_status( array( 'pending', 'on-hold' ) ) ) {
			return;
		}
		$payment_id = $order->get_meta( self::META_PAYMENT_ID );
		if ( '' === $payment_id ) {
			return;
		}
		$payment = $this->api()->get_payment( $payment_id );
		if ( is_wp_error( $payment ) || empty( $payment['status'] ) ) {
			return;
		}
		if ( 'succeeded' === $payment['status'] ) {
			$this->mark_paid( $order, $payment );
			$order->save();
		}
	}
}

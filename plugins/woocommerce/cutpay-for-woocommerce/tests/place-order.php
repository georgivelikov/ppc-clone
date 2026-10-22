<?php
/**
 * Run inside WordPress (wp eval-file): places a WooCommerce order the way checkout does and starts the Cutpay payment.
 *   wp eval-file place-order.php <total> [email]
 * Prints one JSON line: the order id and where the customer would be sent.
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

$total = isset( $args[0] ) ? $args[0] : '25.00';
$email = isset( $args[1] ) ? $args[1] : 'customer@example.com';

wc_load_cart();

$product = new WC_Product_Simple();
$product->set_name( 'Ceramic mug' );
$product->set_regular_price( $total );
$product->set_virtual( true );
$product->save();

$order = wc_create_order();
$order->add_product( $product, 1 );
$order->set_billing_email( $email );
$order->set_billing_first_name( 'Dana' );
$order->set_billing_last_name( 'Customer' );
$order->set_payment_method( 'cutpay' );
$order->calculate_totals();
$order->save();

$gateways = WC()->payment_gateways()->payment_gateways();
$result   = $gateways['cutpay']->process_payment( $order->get_id() );

echo wp_json_encode(
	array(
		'order_id' => $order->get_id(),
		'total'    => $order->get_total(),
		'result'   => $result['result'],
		'redirect' => isset( $result['redirect'] ) ? $result['redirect'] : null,
		'status'   => wc_get_order( $order->get_id() )->get_status(),
	)
) . "\n";

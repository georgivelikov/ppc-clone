<?php
/**
 * Plugin Name:       Cutpay for WooCommerce
 * Description:       Take card and other payments in WooCommerce through Cutpay's hosted checkout. Educational project: no real money moves.
 * Version:           0.1.0
 * Requires at least: 6.4
 * Requires PHP:      7.4
 * Requires Plugins:  woocommerce
 * License:           MIT
 * Text Domain:       cutpay-for-woocommerce
 */

defined( 'ABSPATH' ) || exit;

define( 'CUTPAY_WC_VERSION', '0.1.0' );
define( 'CUTPAY_WC_FILE', __FILE__ );

// Pure helpers first: they need nothing from WordPress, so they can be tested on their own.
require_once __DIR__ . '/includes/class-cutpay-signature.php';
require_once __DIR__ . '/includes/class-cutpay-money.php';

add_action(
	'before_woocommerce_init',
	function () {
		if ( class_exists( \Automattic\WooCommerce\Utilities\FeaturesUtil::class ) ) {
			// Orders are read and written only through the order object, so high-performance order storage is fine.
			\Automattic\WooCommerce\Utilities\FeaturesUtil::declare_compatibility( 'custom_order_tables', __FILE__, true );
			// The block-based checkout is supported through includes/class-cutpay-blocks.php.
			\Automattic\WooCommerce\Utilities\FeaturesUtil::declare_compatibility( 'cart_checkout_blocks', __FILE__, true );
		}
	}
);

add_action(
	'plugins_loaded',
	function () {
		if ( ! class_exists( 'WC_Payment_Gateway' ) ) {
			return;
		}
		require_once __DIR__ . '/includes/class-cutpay-api.php';
		require_once __DIR__ . '/includes/class-wc-gateway-cutpay.php';

		add_filter(
			'woocommerce_payment_gateways',
			function ( $gateways ) {
				$gateways[] = 'WC_Gateway_Cutpay';
				return $gateways;
			}
		);
	}
);

add_action(
	'woocommerce_blocks_loaded',
	function () {
		if ( ! class_exists( '\Automattic\WooCommerce\Blocks\Payments\Integrations\AbstractPaymentMethodType' ) ) {
			return;
		}
		require_once __DIR__ . '/includes/class-cutpay-blocks.php';
		add_action(
			'woocommerce_blocks_payment_method_type_registration',
			function ( $registry ) {
				$registry->register( new Cutpay_Blocks_Payment_Method() );
			}
		);
	}
);

add_filter(
	'plugin_action_links_' . plugin_basename( __FILE__ ),
	function ( $links ) {
		$url = admin_url( 'admin.php?page=wc-settings&tab=checkout&section=cutpay' );
		array_unshift( $links, '<a href="' . esc_url( $url ) . '">' . esc_html__( 'Settings', 'cutpay-for-woocommerce' ) . '</a>' );
		return $links;
	}
);

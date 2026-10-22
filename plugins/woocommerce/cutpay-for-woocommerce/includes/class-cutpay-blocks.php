<?php
/**
 * Makes Cutpay appear in the block-based checkout (the default in current WooCommerce). The classic checkout needs
 * no such thing; the blocks one lists only payment methods that register a script for it.
 */

defined( 'ABSPATH' ) || exit;

use Automattic\WooCommerce\Blocks\Payments\Integrations\AbstractPaymentMethodType;

class Cutpay_Blocks_Payment_Method extends AbstractPaymentMethodType {

	protected $name = 'cutpay';

	public function initialize() {
		$this->settings = get_option( 'woocommerce_cutpay_settings', array() );
	}

	public function is_active() {
		return isset( $this->settings['enabled'] ) && 'yes' === $this->settings['enabled'];
	}

	public function get_payment_method_script_handles() {
		wp_register_script(
			'cutpay-blocks',
			plugins_url( 'assets/blocks.js', CUTPAY_WC_FILE ),
			array( 'wc-blocks-registry', 'wc-settings', 'wp-element', 'wp-html-entities' ),
			CUTPAY_WC_VERSION,
			true
		);
		return array( 'cutpay-blocks' );
	}

	public function get_payment_method_data() {
		return array(
			'title'       => isset( $this->settings['title'] ) ? $this->settings['title'] : 'Cutpay',
			'description' => isset( $this->settings['description'] ) ? $this->settings['description'] : '',
			'supports'    => array( 'products' ),
		);
	}
}

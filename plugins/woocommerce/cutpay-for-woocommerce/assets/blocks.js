( function () {
	var registry = window.wc.wcBlocksRegistry;
	var settings = window.wc.wcSettings.getSetting( 'cutpay_data', {} );
	var el = window.wp.element.createElement;
	var decode = window.wp.htmlEntities.decodeEntities;

	var label = decode( settings.title || 'Cutpay' );
	var content = function () {
		return el( 'span', null, decode( settings.description || '' ) );
	};

	registry.registerPaymentMethod( {
		name: 'cutpay',
		label: el( 'span', null, label ),
		content: el( content, null ),
		edit: el( content, null ),
		canMakePayment: function () {
			return true;
		},
		ariaLabel: label,
		supports: { features: settings.supports || [ 'products' ] },
	} );
} )();

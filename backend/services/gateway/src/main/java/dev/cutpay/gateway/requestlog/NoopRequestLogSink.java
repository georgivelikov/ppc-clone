package dev.cutpay.gateway.requestlog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cutpay.request-log.enabled", havingValue = "false")
class NoopRequestLogSink implements RequestLogSink {

    @Override
    public void publish(RequestLogEntry entry) {
    }
}

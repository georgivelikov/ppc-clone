package dev.cutpay.gateway.requestlog;

/** Where finished requests go. Implementations must not block and must not throw. */
public interface RequestLogSink {

    void publish(RequestLogEntry entry);
}

-- Trace context of the request or message that wrote the event, so the relay can continue the trace
-- (docs/adr/0031-observability.md).
ALTER TABLE outbox ADD COLUMN traceparent TEXT;

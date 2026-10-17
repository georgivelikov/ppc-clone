import { RedocStandalone } from "redoc";
import spec from "../../../../../../docs/api/openapi.yaml?raw";

/** Renders docs/api/openapi.yaml, the single source of truth for the public API, with Redoc. */
export function ApiReferencePage() {
  return (
    <div data-testid="api-reference">
      {/* Redoc accepts the YAML text directly and handles its own links below /docs/api. Telemetry is off. */}
      <RedocStandalone spec={spec} basePath="/docs/api" options={{ disableTelemetry: true }} />
    </div>
  );
}

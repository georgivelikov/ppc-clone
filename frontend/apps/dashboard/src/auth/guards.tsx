import { Loader2 } from "lucide-react";
import { Navigate, Outlet, useLocation } from "react-router";
import { useAuth } from "./AuthProvider";

function FullPageSpinner() {
  return (
    <div className="grid min-h-screen place-items-center" role="status" aria-label="Loading">
      <Loader2 className="size-6 animate-spin text-muted" />
    </div>
  );
}

/** Routes that need a signed-in user. Remembers where the visitor was heading. */
export function RequireAuth() {
  const { state } = useAuth();
  const location = useLocation();
  if (state.status === "loading") return <FullPageSpinner />;
  if (state.status === "anonymous") return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}

/** Login and signup pages bounce signed-in users to the dashboard. */
export function GuestOnly() {
  const { state } = useAuth();
  if (state.status === "loading") return <FullPageSpinner />;
  if (state.status === "authenticated") return <Navigate to="/" replace />;
  return <Outlet />;
}

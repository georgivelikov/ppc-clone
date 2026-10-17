// Where the other apps live in development. In production these would be real domains.
export const DASHBOARD_URL = import.meta.env.VITE_DASHBOARD_URL ?? "http://localhost:5173";
export const SIGNUP_URL = `${DASHBOARD_URL}/signup`;
export const LOGIN_URL = `${DASHBOARD_URL}/login`;
export const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";
export const CHECKOUT_URL = import.meta.env.VITE_CHECKOUT_URL ?? "http://localhost:5174";

// A business's application to be approved for live payments (backend/services/identity, onboarding package).

export type BusinessType = "sole_trader" | "company" | "nonprofit";
export type PersonRole = "representative" | "ubo" | "director";
export type VerificationStatus = "not_started" | "pending" | "approved" | "rejected" | "needs_more_info";
export type DocumentKind = "id_front" | "id_back" | "selfie" | "bank_proof";
export type ApplicationStep = "business" | "people" | "documents" | "bank" | "profile";

export interface RegisteredCompany {
  registration_number: string;
  country: string;
  name: string;
  legal_form: string;
  status: "active" | "dissolved";
  incorporated_on: string;
  address: { line1: string; city: string; postal_code: string };
  representatives: { name: string; role: string }[];
}

export interface ApplicationData {
  business_type?: BusinessType;
  industry?: string;
  country?: string;
  registration_number?: string;
  company?: RegisteredCompany;
  website?: string;
  description?: string;
  /** In euro cents. */
  expected_monthly_volume?: number;
  iban?: string;
  account_holder?: string;
}

export interface ApplicationPerson {
  id: string;
  role: PersonRole;
  name: string;
  date_of_birth: string;
  nationality: string;
  ownership_pct: number | null;
  document_type: string;
  document_number: string;
  verification_status: VerificationStatus;
  verification_reason: string | null;
  screening: "clear" | "pep" | "sanctions" | null;
}

export interface ApplicationDocument {
  id: string;
  person_id: string | null;
  type: DocumentKind;
  filename: string;
  content_type: string;
  size: number;
  uploaded_at: string;
}

export interface Application {
  id: string;
  merchant_id: string;
  status: "pending_kyc" | "in_review" | "needs_more_info" | "active" | "rejected" | "suspended";
  data: ApplicationData;
  persons: ApplicationPerson[];
  documents: ApplicationDocument[];
  /** The steps still to finish before the application can be submitted. */
  missing: ApplicationStep[];
  risk_score: number | null;
  risk_reasons: string[];
  manual_review: boolean;
  submitted_at: string | null;
  decided_at: string | null;
  decided_by: string | null;
  decision_reason: string | null;
  signed_name: string | null;
}

export interface PersonInput {
  role: PersonRole;
  name: string;
  date_of_birth: string;
  nationality: string;
  ownership_pct?: number | null;
  document_type: string;
  document_number: string;
}

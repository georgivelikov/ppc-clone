import { PageHeader, Section } from "../components";

/** The address security reports go to; the same one is in /.well-known/security.txt. */
export const SECURITY_EMAIL = "security@cutpay.example";

export function SecurityPage() {
  return (
    <>
      <PageHeader
        title="Security"
        intro="How we protect payments, and how to tell us when we got something wrong."
      />
      <div className="mx-auto max-w-3xl space-y-10 px-4 pb-16">
        <Section title="How card data is handled">
          <ul className="list-disc space-y-2 pl-5 text-sm text-muted">
            <li>
              Card numbers are typed into a field served from Cutpay&apos;s domain, either in an iframe or on the hosted page. Your
              own page and server never see them, which keeps most of your card-data compliance work out of scope.
            </li>
            <li>
              The card is exchanged for a one-time token by the vault. Payments use the token; the number is not stored with the
              payment.
            </li>
            <li>
              Bank account numbers are encrypted with keys held in a key service, not in the database, and API responses show only
              the country and last four digits.
            </li>
            <li>
              Pages that handle payments send a strict Content-Security-Policy, refuse to be framed (except the card field) and
              do not leak the payment address in a Referer header. Our script can be pinned to a version and checked with
              Subresource Integrity.
            </li>
          </ul>
        </Section>

        <Section title="Reporting a vulnerability">
          <p className="text-sm text-muted">
            If you find a security problem, please tell us before anyone else. Email{" "}
            <a className="text-brand-700 hover:underline" href={`mailto:${SECURITY_EMAIL}`}>
              {SECURITY_EMAIL}
            </a>{" "}
            with what you found, how to reproduce it and what you think it affects. Please do not include real card data or
            personal data you came across; a description is enough.
          </p>
          <ul className="mt-4 list-disc space-y-2 pl-5 text-sm text-muted">
            <li>We acknowledge a report within three working days and tell you what we plan to do within ten.</li>
            <li>We will not take legal action against research done in good faith that follows this page.</li>
            <li>Keep to your own test accounts and test mode, stop as soon as you have shown the problem, and give us a reasonable time to fix it before you publish.</li>
            <li>We are glad to credit you once it is fixed, if you want to be named.</li>
          </ul>
        </Section>

        <Section title="Out of scope">
          <ul className="list-disc space-y-2 pl-5 text-sm text-muted">
            <li>Denial of service, volumetric traffic, and automated scanner output with no demonstrated impact.</li>
            <li>Social engineering of staff, physical access, and anything that needs a rooted or already-compromised device.</li>
            <li>Missing best-practice headers on pages that handle no payments or personal data.</li>
            <li>The test-mode simulators (card network, bank, identity checks) behaving like simulators.</li>
          </ul>
          <p className="mt-4 text-sm text-muted">
            Cutpay is an educational project and pays no bounties, but we do read every report.
          </p>
        </Section>
      </div>
    </>
  );
}

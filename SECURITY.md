# Security policy

FraudShield is a research project under active development and is **not** production
software. It must not process real customer data; development and demo environments use
synthetic data only.

## Reporting a vulnerability

Please report suspected vulnerabilities privately through GitHub's
"Report a vulnerability" (private security advisory) feature on this repository rather than
opening a public issue. Include the affected component, reproduction steps and impact.

## Handling of secrets in this repository

- No credentials are committed. Local credentials are generated into a git-ignored `.env`
  (`make env`); CI runs Gitleaks on every push, and a pre-commit hook blocks secrets locally.
- If a secret is ever committed, it is treated as compromised: it is rotated first, then the
  history is cleaned with the maintainer's knowledge.

## Scope of security assurance

Automated scanning (Gitleaks now; dependency, container and OWASP ZAP scans from M9) does not
replace an independent penetration test, which requires an external party before any
production use (SRS 4.2, D-28).

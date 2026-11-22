# Security policy

This library handles secrets, so security reports get priority.

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

**Please don't open a public issue.** Report it privately through GitHub's private vulnerability reporting: open the repository's **Security** tab and choose **Report a vulnerability**.

Please include the library version, the Java version (and the Spring Boot version, if you use the adapter), and the steps to reproduce. Never include real secrets, client IDs, client secrets or tokens.

## What counts

For example:

- a secret, client ID, client secret or access token ending up in an exception message, `toString()`, a log line or a request to another host
- a response from a server that makes the client crash with an unexpected exception, hang, or use unbounded memory
- weakened TLS, redirects being followed, or header injection

## What happens next

We acknowledge the report, confirm and fix the problem, publish a GitHub security advisory, and release a fixed version. We credit reporters who want to be named.

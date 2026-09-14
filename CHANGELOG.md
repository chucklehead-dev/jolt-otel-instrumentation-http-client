# Changelog

## Unreleased

- Follow the merged `casselc/http-client` v0.0.10 convergence line and its
  library-owned `v0.0.10+http-client-core-aspect.1` compatibility identity.
  The provider continues to consume the existing request seam and adds no
  transport or retry behavior. One client span covers the complete logical
  request, including any idempotency-gated stale-connection retry performed
  below the seam; ambiguous non-idempotent failures remain errors.

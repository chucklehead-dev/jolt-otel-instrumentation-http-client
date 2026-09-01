# jolt-otel-instrumentation-http-client

Compiler-selected OpenTelemetry instrumentation for the provider-neutral
`jolt-lang/http-client` manifest. The HTTP client library publishes only an
inert semantic join point. Applications explicitly select this separate
consumer while building a woven binary; putting either package on the
classpath does not activate instrumentation.

The initial contract instruments the normalized, synchronous
`clj-http.lite.core/request` entry. Each logical core request receives one
`CLIENT` span with the current stable HTTP semantic-convention names:

- the canonical `http.request.method`, with unknown methods collapsed to
  `_OTHER` and the span name `HTTP`;
- `server.address`, `server.port`, `url.scheme`, and a sanitized `url.full`
  (path and query keys retained, every query value redacted) when their safe
  components are available;
- `http.response.status_code`, plus `error.type` and error status for HTTP
  errors;
- the stable `http.client.request.duration` histogram in seconds with the
  standard advisory buckets; and
- a correlated `http.client.request.exception` log event at WARN severity,
  carrying only the canonical exception type, when transport work throws.

The provider injects the new client span using W3C Trace Context. It removes
every case spelling of an existing `traceparent` or `tracestate` from a copied
header map before adding the fresh lowercase fields. Baggage is intentionally
not injected by this strict initial contract.

The default known-method set is the current HTTP semantic-convention set.
`OTEL_INSTRUMENTATION_HTTP_KNOWN_METHODS` supplies the specified comma-separated,
case-sensitive full override for applications using extension methods.

Request and response bodies, arbitrary headers, user info, query values,
fragments, exception messages, and exception data are never retained. The URL
path is retained because it is part of the current required sanitized
`url.full` convention; query keys remain while every value becomes `REDACTED`.
Application results and thrown values retain their exact identity. Generic instrumentation
suppression bypasses even request inspection, preventing exporter and
observability-viewer feedback.

## Select it in a build

Add this package, the matching HTTP client revision, and OTel to the
application dependency graph, then select the library manifest:

```clojure
{:jolt/build
 {:aspects
  [{:resource "META-INF/jolt/aspects/http-client-core.edn"
    :provider otel.instrumentation.http-client}]
  :aspect-report "target/http-client-aspects.edn"}}
```

The provider accepts only the manifest's exact source-seam compatibility id.
A changed HTTP request boundary must publish a new id and be reviewed before it
can be selected again.

The current platform shim may follow redirects below this semantic entry. Such
a redirect chain is therefore represented by the one allowed top-level logical
client span rather than a span per wire attempt. A future attempt-level seam
can add resend attributes without weakening this stable library contract.

## Verification

The provider and its library-owned manifest are pinned to exact published
revisions. From this repository, with the workspace's pinned Chez toolchain:

```sh
export ASPECT_JOLT=/absolute/path/to/an/aspect-capable-jolt

env JOLT_GITLIBS_DIR=/home/chuck/.cache/jolt-http-instrumentation-gitlibs \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$ASPECT_JOLT" -M:test

cd test-app
env JOLT_CACHE_DIR=/home/chuck/.cache/jolt-http-instrumentation-woven \
  JOLT_GITLIBS_DIR=/home/chuck/.cache/jolt-http-instrumentation-gitlibs \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  "$ASPECT_JOLT" \
  build -m instrumentation-fixture.main \
  -o target/woven-http-client-fixture

target/woven-http-client-fixture

cd ../test-app-plain
env JOLT_CACHE_DIR=/home/chuck/.cache/jolt-http-instrumentation-plain \
  JOLT_GITLIBS_DIR=/home/chuck/.cache/jolt-http-instrumentation-gitlibs \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  "$ASPECT_JOLT" \
  build -m instrumentation-fixture.main \
  -o target/plain-http-client-fixture

target/plain-http-client-fixture plain
```

Jolt v0.8.0 is the minimum runtime for the provider's pinned HTTP client: that
release changed `ffi/write` to the value-before-offset contract used by its
native compression path. Compiler-selected aspects are newer than v0.8.0, so
the provider tests and both binary fixtures deliberately use the explicit
aspect-capable build shown above. Using the same compiler for both fixtures
proves that selection, rather than classpath presence, controls weaving.

The compiler fixture makes a real loopback HTTP request. Its server echoes the
wire `traceparent`, and the application verifies it identifies the generated
client span, that the span is a direct child of the active parent, and that the
matching duration metric carries the method and response status.
`test-app-plain` builds the same source with the provider and inert manifest on
the classpath but no `:jolt/build :aspects` selection; running it with `plain`
verifies that it emits no HTTP span and sends no synthetic trace header.

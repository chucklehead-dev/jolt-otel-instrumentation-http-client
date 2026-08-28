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
- `server.address`, `server.port`, `url.scheme`, and a privacy-redacted
  `url.full` when their safe components are available;
- `http.response.status_code`, plus `error.type` and error status for HTTP
  errors; and
- a canonical exception type and a message-free exception event when transport
  work throws.

The provider injects the new client span using W3C Trace Context. It removes
every case spelling of an existing `traceparent` or `tracestate` from a copied
header map before adding the fresh lowercase fields. Baggage is intentionally
not injected by this strict initial contract.

The default known-method set is the current HTTP semantic-convention set.
`OTEL_INSTRUMENTATION_HTTP_KNOWN_METHODS` supplies the specified comma-separated,
case-sensitive full override for applications using extension methods.

Request and response bodies, arbitrary headers, user info, URI paths, query
strings, fragments, exception messages, and exception data are never retained.
The redacted URL preserves the HTTP origin, then represents any non-root path
and any query as `REDACTED`; this is an explicit privacy-first reduction of the
otherwise-full `url.full` convention. Application results and thrown values
retain their exact identity. Generic instrumentation suppression bypasses even
request inspection, preventing exporter and observability-viewer feedback.

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
env JOLT_CACHE_DIR=/home/chuck/.cache/jolt-http-instrumentation-23d46d4c \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  /home/chuck/ai-src/worktrees/jolt-aspect-manifest-build-hook/target/release/jolt \
  -M:test

cd test-app
env JOLT_CACHE_DIR=/home/chuck/.cache/jolt-http-instrumentation-build-23d46d4c \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  /home/chuck/ai-src/worktrees/jolt-aspect-manifest-build-hook/target/release/jolt \
  build -m instrumentation-fixture.main \
  -o target/woven-http-client-fixture

target/woven-http-client-fixture
```

The compiler fixture makes a real loopback HTTP request. Its server echoes the
wire `traceparent`, and the application verifies it identifies the generated
client span and that the span is a direct child of the active parent.
`test-app-plain` builds the same source with the provider and inert manifest on
the classpath but no `:jolt/build :aspects` selection; running it with `plain`
verifies that it emits no HTTP span and sends no synthetic trace header.

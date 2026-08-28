(ns otel.instrumentation.http-client
  "Build-selected OpenTelemetry instrumentation for jolt-lang/http-client.

  The library-owned manifest selects the normalized, synchronous
  `clj-http.lite.core/request` entry. This provider creates one CLIENT span for
  that logical operation and injects only W3C Trace Context into a copied
  request map.

  Privacy is deliberately bounded: request/response bodies, arbitrary headers,
  user info, URI paths, query strings, fragments, and exception messages/data
  are never retained. The destination host and port remain because they are
  the stable HTTP peer identity. `url.full` is a redacted absolute URL which
  keeps only the origin and whether a path/query existed."
  (:require [clojure.string :as str]
            [otel.context :as context]
            [otel.propagation :as propagation]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(def http-client-build-id
  "Compatibility id of the exact jolt-lang/http-client source seam selected by
  the inert library manifest."
  "12b78edb9024d200083cf77d61fa56709ab23dd7")

(def ^:private instrumentation-version "0.1.0")
(def ^:private scope-name
  "io.github.chucklehead-dev/jolt-otel-instrumentation-http-client")

(def ^:private default-known-methods
  #{"CONNECT" "DELETE" "GET" "HEAD" "OPTIONS" "PATCH" "POST" "PUT"
    "QUERY" "TRACE"})

(defn parse-known-methods
  "Parse OTEL_INSTRUMENTATION_HTTP_KNOWN_METHODS as a full, case-sensitive
  override. Invalid/blank entries are ignored; an absent value selects the
  current standard default set."
  [raw]
  (if (string? raw)
    (into #{}
          (filter #(and (<= 1 (count %) 32)
                        (re-matches #"[!#$%&'*+.^_`|~0-9A-Za-z-]+" %)))
          (map str/trim (str/split raw #",")))
    default-known-methods))

(def ^:private configured-known-methods
  (parse-known-methods (System/getenv "OTEL_INSTRUMENTATION_HTTP_KNOWN_METHODS")))

(defn method-name
  "Return the current HTTP semconv method value from a normalized request.
  Unknown or malformed methods use the required low-cardinality `_OTHER`."
  [request-method]
  (let [candidate
        (cond
          (keyword? request-method) (str/upper-case (name request-method))
          (string? request-method) (str/upper-case request-method)
          :else nil)]
    (if (contains? configured-known-methods candidate) candidate "_OTHER")))

(defn- original-method [request-method]
  (let [candidate
        (cond
          (keyword? request-method) (str/upper-case (name request-method))
          (string? request-method) (str/upper-case request-method)
          :else nil)]
    (when (and (= "_OTHER" (method-name request-method))
               (string? candidate)
               (<= 1 (count candidate) 32)
               (re-matches #"[!#$%&'*+.^_`|~0-9A-Z-]+" candidate))
      candidate)))

(defn- safe-scheme [value]
  (let [candidate (cond
                    (keyword? value) (name value)
                    (string? value) (str/lower-case value)
                    :else nil)]
    (when (contains? #{"http" "https"} candidate) candidate)))

(defn- safe-host [value]
  (when (and (string? value)
             (pos? (count value))
             (<= (count value) 255)
             (not (re-find #"[/?#@\s]" value)))
    value))

(defn- safe-port [value]
  (when (and (integer? value) (<= 1 value 65535)) value))

(defn- default-port? [scheme port]
  (or (and (= "http" scheme) (= 80 port))
      (and (= "https" scheme) (= 443 port))))

(defn- effective-port [scheme explicit]
  (or explicit
      (case scheme
        "http" 80
        "https" 443
        nil)))

(defn- url-host [host]
  (if (and host (.contains host ":")
           (not (and (.startsWith host "[") (.endsWith host "]"))))
    (str "[" host "]")
    host))

(defn- redacted-url [scheme host port uri query-string]
  (when (and scheme host)
    (str scheme "://" (url-host host)
         (when (and port (not (default-port? scheme port))) (str ":" port))
         (if (or (nil? uri) (= "" uri) (= "/" uri)) "/" "/REDACTED")
         (when (and (string? query-string) (pos? (count query-string)))
           "?REDACTED"))))

(defn request-attributes
  "Build the bounded span-start attributes for a normalized request map."
  [request]
  (let [method (method-name (:request-method request))
        original (original-method (:request-method request))
        scheme (safe-scheme (:scheme request))
        host (safe-host (:server-name request))
        port (effective-port scheme (safe-port (:server-port request)))
        url (redacted-url scheme host port (:uri request) (:query-string request))]
    (cond-> {:http.request.method method}
      original (assoc :http.request.method_original original)
      scheme (assoc :url.scheme scheme)
      host (assoc :server.address host)
      (and host port) (assoc :server.port port)
      url (assoc :url.full url))))

(defn- header-name [key]
  (try
    (str/lower-case (if (keyword? key) (name key) (str key)))
    (catch :default _ "")))

(defn- clear-trace-context [headers]
  (reduce (fn [result [key value]]
            (if (contains? #{"traceparent" "tracestate"} (header-name key))
              result
              (assoc result key value)))
          {}
          (if (map? headers) headers {})))

(defn- inject-trace-context [request]
  (let [carrier (propagation/inject-current
                 propagation/trace-context
                 (clear-trace-context (:headers request)))]
    (assoc request :headers carrier)))

(defn- exception-type [error]
  (try
    (or (some-> error class .getName) "UnknownExceptionType")
    (catch :default _ "UnknownExceptionType")))

(defn- set-response-status! [span response]
  ;; Observing an unusual application result must not replace that result.
  (try
    (when-let [status (and (map? response) (:status response))]
      (when (integer? status)
        (trace/set-attribute! span :http.response.status_code status)
        (when (or (< status 100) (>= status 400))
          (trace/set-attribute! span :error.type (str status))
          (trace/set-status! span :error))))
    (catch :default _ nil)))

(defn- record-failure! [span error]
  (try
    (let [type (exception-type error)]
      (trace/set-attribute! span :error.type type)
      (trace/add-event! span "exception"
                        {:exception.type type
                         :exception.escaped true})
      (trace/set-status! span :error "HTTP client request failed"))
    (catch :default _ nil)))

(defn- span-name [attributes]
  (let [method (:http.request.method attributes)]
    (if (= "_OTHER" method) "HTTP" method)))

(defn- traced [request proceed]
  (let [attributes (request-attributes request)
        tracer (sdk/tracer scope-name {:version instrumentation-version})
        span (trace/start-span tracer (span-name attributes)
                               {:kind :client :attributes attributes})]
    (try
      (trace/with-current-span span
        (try
          (let [response (proceed [(inject-trace-context request)])]
            (set-response-status! span response)
            response)
          (catch :default error
            (record-failure! span error)
            (throw error))))
      (finally
        (trace/end! span)))))

(defn around
  "Instrument one normalized synchronous HTTP request.

  This implements the compiler's `:replace-args-v1` contract. The replacement
  is a copied request map containing fresh lowercase Trace Context fields;
  existing traceparent/tracestate spellings are removed first. Application
  results and thrown values retain their exact identity. Generic suppression
  bypasses all request inspection and propagation."
  [_join-point [request] proceed]
  (if (context/instrumentation-suppressed?)
    (proceed)
    (traced request proceed)))

(def aspect-provider
  {:schema 1
   :libraries {'jolt-lang/http-client http-client-build-id}
   :roles {:http/client
           {:fn 'otel.instrumentation.http-client/around
            :contract :replace-args-v1}}})

(ns otel.instrumentation.http-client-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [otel.context :as context]
            [otel.exporter.memory :as memory]
            [otel.instrumentation.http-client :as instrumentation]
            [otel.metrics :as metrics]
            [otel.propagation :as propagation]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(defn- join-point []
  {:id :http-client.core/request
   :advice-role :http/client
   :contract :replace-args-v1
   :match {:entry 'clj-http.lite.core/request :arity 1}
   :library {:id 'jolt-lang/http-client
             :version instrumentation/http-client-build-id}})

(defn- with-memory-sdk [f]
  (let [exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "http-client-instrumentation-test"
                           :exporter exporter
                           :processor :simple
                           :runtime-metrics? false
                           :logs? true
                           :bridge-logging? false})]
    (try
      (f exporter)
      (finally
        (sdk/shutdown! handle)))))

(deftest method-classification-is-closed-and-low-cardinality
  (doseq [[input expected]
          [[:get "GET"] ["post" "POST"] [:patch "PATCH"]
           [:private-method "_OTHER"] ["secret method" "_OTHER"]
           [nil "_OTHER"]]]
    (is (= expected (instrumentation/method-name input)))))

(deftest known-method-override-parser-is-bounded-and-a-full-override
  (is (contains? (instrumentation/parse-known-methods nil) "GET"))
  (is (= #{"ACL" "PURGE"}
         (instrumentation/parse-known-methods "ACL, PURGE")))
  (is (= #{"ACL"}
         (instrumentation/parse-known-methods
          "ACL,invalid method,TOO-LONG-ABCDEFGHIJKLMNOPQRSTUVWXYZ"))))

(deftest unknown-valid-method-keeps-the-required-bounded-original
  (let [attrs (instrumentation/request-attributes
               {:request-method "private-method"
                :scheme :https :server-name "example.test" :uri "/"})
        malformed (instrumentation/request-attributes
                   {:request-method "secret method with spaces"
                    :scheme :https :server-name "example.test" :uri "/"})]
    (is (= "_OTHER" (:http.request.method attrs)))
    (is (= "PRIVATE-METHOD" (:http.request.method_original attrs)))
    (is (= "_OTHER" (:http.request.method malformed)))
    (is (nil? (:http.request.method_original malformed)))
    (is (= 443 (:server.port attrs)))))

(deftest result-identity-parentage-propagation-and-privacy
  (with-memory-sdk
    (fn [exporter]
      (let [request {:request-method :post
                     :scheme :https
                     :server-name "api.example.test"
                     :server-port 8443
                     :uri "/customers/super-secret-id"
                     :query-string "token=super-secret-query"
                     :headers {"Authorization" "Bearer super-secret-auth"
                               "TraceParent" "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"
                               "TRACESTATE" "private=secret"}
                     :body "super-secret-body"}
            response {:status 202
                      :headers {"x-secret" "super-secret-response-header"}
                      :body "super-secret-response-body"}
            captured (atom nil)
            tracer (sdk/tracer "http-client-parent")
            observed
            (trace/with-span [_parent tracer "request"]
              (instrumentation/around
               (join-point) [request]
               (fn [replacement]
                 (reset! captured replacement)
                 response)))
            spans (memory/spans exporter)
            parent (first (filter #(= "request" (:name %)) spans))
            client (first (filter #(= "POST" (:name %)) spans))
            injected (get-in @captured [0 :headers "traceparent"])
            serialized (pr-str client)]
        (is (identical? response observed))
        (is (= :client (:kind client)))
        (is (= (get-in parent [:span-context :span-id])
               (:parent-span-id client)))
        (is (= (str "00-" (get-in client [:span-context :trace-id]) "-"
                    (get-in client [:span-context :span-id]) "-01")
               injected))
        (is (= "Bearer super-secret-auth"
               (get-in @captured [0 :headers "Authorization"])))
        (is (nil? (get-in @captured [0 :headers "TraceParent"])))
        (is (nil? (get-in @captured [0 :headers "TRACESTATE"])))
        (is (= "POST" (get (:attributes client) "http.request.method")))
        (is (= "api.example.test"
               (get (:attributes client) "server.address")))
        (is (= 8443 (get (:attributes client) "server.port")))
        (is (= "https://api.example.test:8443/customers/super-secret-id?token=REDACTED"
               (get (:attributes client) "url.full")))
        (is (= 202 (get (:attributes client) "http.response.status_code")))
        (doseq [secret ["super-secret-query" "super-secret-auth" "super-secret-body"
                        "super-secret-response-header"
                        "super-secret-response-body" "private=secret"]]
          (is (not (.contains serialized secret))))))))

(deftest remote-parent-and-tracestate-cross-the-client-boundary
  (with-memory-sdk
    (fn [exporter]
      (let [upstream-trace-id "0af7651916cd43dd8448eb211c80319c"
            upstream-span-id "b7ad6b7169203331"
            upstream
            (propagation/extract-context
             propagation/trace-context
             {"traceparent" (str "00-" upstream-trace-id "-"
                                  upstream-span-id "-01")
              "tracestate" "vendor=opaque"})
            captured (atom nil)]
        (context/with-context upstream
          (instrumentation/around
           (join-point)
           [{:request-method :get :scheme :https
             :server-name "example.test" :uri "/" :headers {}}]
           (fn [replacement]
             (reset! captured replacement)
             {:status 200})))
        (let [client (first (memory/spans exporter))
              traceparent (get-in @captured [0 :headers "traceparent"])]
          (is (= upstream-trace-id (get-in client [:span-context :trace-id])))
          (is (= upstream-span-id (:parent-span-id client)))
          (is (.startsWith traceparent (str "00-" upstream-trace-id "-")))
          (is (= "vendor=opaque"
                 (get-in @captured [0 :headers "tracestate"]))))))))

(deftest error-status-follows-current-client-semconv
  (with-memory-sdk
    (fn [exporter]
      (instrumentation/around
       (join-point)
       [{:request-method :get :scheme :http :server-name "example.test"
         :uri "/"}]
       (fn [_] {:status 503}))
      (let [span (first (memory/spans exporter))]
        (is (= 503 (get (:attributes span) "http.response.status_code")))
        (is (= "503" (get (:attributes span) "error.type")))
        (is (= :error (get-in span [:status :code])))
        (is (nil? (get-in span [:status :description])))))))

(deftest exception-identity-and-message-privacy
  (with-memory-sdk
    (fn [exporter]
      (let [failure (ex-info "super-secret failure" {:token "private-token"})
            observed
            (try
              (instrumentation/around
               (join-point)
               [{:request-method :get :scheme :https
                 :server-name "example.test" :uri "/private-path"}]
               (fn [_] (throw failure)))
              (catch :default error error))
            span (first (memory/spans exporter))
            event (first (memory/records exporter))
            serialized (pr-str [span event])]
        (is (identical? failure observed))
        (is (= :error (get-in span [:status :code])))
        (is (= "clojure.lang.ExceptionInfo"
               (get (:attributes span) "error.type")))
        (is (empty? (:events span)))
        (is (= "http.client.request.exception" (:event-name event)))
        (is (= 13 (:severity-number event)))
        (is (= "clojure.lang.ExceptionInfo"
               (get (:attributes event) "exception.type")))
        (is (= (get-in span [:span-context :trace-id]) (:trace-id event)))
        (is (= (get-in span [:span-context :span-id]) (:span-id event)))
        (doseq [secret ["super-secret failure" "private-token"]]
          (is (not (.contains serialized secret))))))))

(deftest duration-metric-uses-stable-name-unit-buckets-and-attributes
  (let [exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "http-client-metric-test"
                           :exporter exporter :processor :simple
                           :runtime-metrics? false :logs? true
                           :bridge-logging? false})]
    (try
      (instrumentation/around
       (join-point)
       [{:request-method :get :scheme :https :server-name "example.test"
         :server-port 8443 :uri "/items"}]
       (fn [_] {:status 200}))
      (is (sdk/force-flush! handle))
      (let [metric (first (filter #(= "http.client.request.duration" (:name %))
                                  (memory/metrics exporter)))
            point (first (:data-points metric))
            span (first (memory/spans exporter))
            span-duration (/ (- (:end-time-unix-nano span)
                                (:start-time-unix-nano span))
                             1000000000.0)]
        (is (= "s" (:unit metric)))
        (is (= [0.005 0.01 0.025 0.05 0.075 0.1 0.25 0.5 0.75
                1.0 2.5 5.0 7.5 10.0]
               (:explicit-bounds metric)))
        (is (= "GET" (get (:attributes point) "http.request.method")))
        (is (= "example.test" (get (:attributes point) "server.address")))
        (is (= 8443 (get (:attributes point) "server.port")))
        (is (= 200 (get (:attributes point) "http.response.status_code")))
        (is (not (neg? (:sum point))))
        (is (= span-duration (:sum point))))
      (finally
        (sdk/shutdown! handle)))))

(deftest telemetry-finalization-never-masks-application-outcomes
  (let [result (Object.)
        failure (ex-info "application failure" {:private true})]
    (with-redefs [trace/end! (fn [& _]
                               (throw (ex-info "telemetry end failed" {})))
                  metrics/record! (fn [& _]
                                    (throw (ex-info "telemetry metric failed" {})))]
      (is (identical?
           result
           (instrumentation/around
            (join-point)
            [{:request-method :get :scheme :https
              :server-name "example.test" :uri "/"}]
            (fn [_] result))))
      (is (identical?
           failure
           (try
             (instrumentation/around
              (join-point)
              [{:request-method :get :scheme :https
                :server-name "example.test" :uri "/"}]
              (fn [_] (throw failure)))
             (catch :default error error)))))))

(deftest suppression-bypasses-request-inspection-and-propagation
  (with-memory-sdk
    (fn [exporter]
      (let [request (Object.)
            result (Object.)
            called (atom nil)
            observed
            (context/with-instrumentation-suppressed
              (instrumentation/around
               (join-point) [request]
               (fn []
                 (reset! called true)
                 result)))]
        (is (identical? result observed))
        (is (true? @called))
        (is (empty? (memory/spans exporter)))))))

(deftest provider-contract-is-exact
  (is (= {:schema 1
          :libraries {'jolt-lang/http-client
                      instrumentation/http-client-build-id}
          :roles {:http/client
                  {:fn 'otel.instrumentation.http-client/around
                   :contract :replace-args-v1}}}
         instrumentation/aspect-provider)))

(deftest provider-version-matches-fetched-library-manifest
  (let [resource (io/resource "META-INF/jolt/aspects/http-client-core.edn")
        manifest (some-> resource slurp edn/read-string)]
    (is (some? resource))
    (is (= 'jolt-lang/http-client (get-in manifest [:library :id])))
    (is (= instrumentation/http-client-build-id
           (get-in manifest [:library :version])))
    (is (= {:entry 'clj-http.lite.core/request :arity 1}
           (get-in manifest [:aspects 0 :match])))))

(deftest propagator-scope-is-trace-context-only
  (is (= #{"traceparent" "tracestate"}
         (set (propagation/fields propagation/trace-context)))))

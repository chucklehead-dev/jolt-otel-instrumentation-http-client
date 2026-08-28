(ns otel.instrumentation.http-client-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [otel.context :as context]
            [otel.exporter.memory :as memory]
            [otel.instrumentation.http-client :as instrumentation]
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
  (let [exporter (memory/exporter)
        handle (sdk/init! {:service-name "http-client-instrumentation-test"
                           :exporter exporter
                           :processor :simple
                           :metrics? false})]
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
        (is (= "https://api.example.test:8443/REDACTED?REDACTED"
               (get (:attributes client) "url.full")))
        (is (= 202 (get (:attributes client) "http.response.status_code")))
        (doseq [secret ["super-secret-id" "super-secret-query"
                        "super-secret-auth" "super-secret-body"
                        "super-secret-response-header"
                        "super-secret-response-body" "private=secret"]]
          (is (not (.contains serialized secret))))))))

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
            serialized (pr-str span)]
        (is (identical? failure observed))
        (is (= :error (get-in span [:status :code])))
        (is (= "clojure.lang.ExceptionInfo"
               (get (:attributes span) "error.type")))
        (is (= "exception" (get-in span [:events 0 :name])))
        (is (= true (get-in span [:events 0 :attributes "exception.escaped"])))
        (doseq [secret ["super-secret failure" "private-token" "private-path"]]
          (is (not (.contains serialized secret))))))))

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

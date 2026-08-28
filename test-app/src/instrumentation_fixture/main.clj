(ns instrumentation-fixture.main
  (:require [clj-http.lite.core :as http]
            [jolt.http.platform]
            [jolt.http.server :as server]
            [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(defn- find-span [spans span-name]
  (first (filter #(= span-name (:name %)) spans)))

(defn- find-metric [metrics metric-name]
  (first (filter #(= metric-name (:name %)) metrics)))

(defn- check! [message expected actual]
  (when-not (= expected actual)
    (throw (ex-info message {:expected expected :actual actual}))))

(defn -main [& args]
  (let [exporter (memory/multisignal-exporter)
        sdk-handle (sdk/init! {:service-name "woven-http-client-fixture"
                               :exporter exporter
                               :processor :simple
                               :runtime-metrics? false
                               :logs? true
                               :bridge-logging? false})
        http-server (server/run-server
                     (fn [request]
                       {:status 200
                        :headers {"Content-Type" "text/plain"}
                        :body (or (get-in request [:headers "traceparent"]) "")})
                     :port 0 :reuse-address? true)
        woven? (not= "plain" (first args))]
    (try
      (let [tracer (sdk/tracer "fixture")
            response
            (trace/with-span [_parent tracer "fixture-parent"]
              (http/request {:request-method :get
                             :scheme :http
                             :server-name "127.0.0.1"
                             :server-port (:port http-server)
                             :uri "/traceparent"
                             :headers {}}))
            echoed (String. (:body response) "UTF-8")
            ;; An SDK is allowed to report an empty flush as false. The signal
            ;; assertions below are the useful contract for these fixtures.
            _ (sdk/force-flush! sdk-handle)
            spans (memory/spans exporter)
            parent (find-span spans "fixture-parent")
            client (find-span spans "GET")
            metric (find-metric (memory/metrics exporter)
                                "http.client.request.duration")
            metric-point (first (:data-points metric))
            expected-header
            (when woven?
              (str "00-" (get-in client [:span-context :trace-id]) "-"
                   (get-in client [:span-context :span-id]) "-01"))]
        (check! "HTTP response status" 200 (:status response))
        (if woven?
          (do
            (check! "W3C traceparent reached the real server" expected-header echoed)
            (check! "woven client span is a direct child"
                    (get-in parent [:span-context :span-id])
                    (:parent-span-id client))
            (check! "woven span kind" :client (:kind client))
            (check! "current method attribute" "GET"
                    (get (:attributes client) "http.request.method"))
            (check! "HTTP client duration metric method" "GET"
                    (get (:attributes metric-point) "http.request.method"))
            (check! "HTTP client duration metric status" 200
                    (get (:attributes metric-point)
                         "http.response.status_code"))
            (println "OK: woven HTTP client span, metric, and tracecontext propagation"))
          (do
            (check! "plain build sends no synthetic traceparent" "" echoed)
            (check! "plain build emits no HTTP client span" nil client)
            (check! "plain build emits no HTTP client metric" nil metric)
            (println "OK: plain build remains uninstrumented"))))
      (finally
        (server/stop-server http-server)
        (sdk/shutdown! sdk-handle)))))

(ns instrumentation-fixture.main
  (:require [clj-http.lite.core :as http]
            [jolt.http.platform]
            [jolt.http.server :as server]
            [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(defn- find-span [spans span-name]
  (first (filter #(= span-name (:name %)) spans)))

(defn- check! [message expected actual]
  (when-not (= expected actual)
    (throw (ex-info message {:expected expected :actual actual}))))

(defn -main [& args]
  (let [exporter (memory/exporter)
        sdk-handle (sdk/init! {:service-name "woven-http-client-fixture"
                               :exporter exporter
                               :processor :simple
                               :metrics? false})
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
            spans (memory/spans exporter)
            parent (find-span spans "fixture-parent")
            client (find-span spans "GET")
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
            (println "OK: woven HTTP client span and tracecontext propagation"))
          (do
            (check! "plain build sends no synthetic traceparent" "" echoed)
            (check! "plain build emits no HTTP client span" nil client)
            (println "OK: plain build remains uninstrumented"))))
      (finally
        (server/stop-server http-server)
        (sdk/shutdown! sdk-handle)))))

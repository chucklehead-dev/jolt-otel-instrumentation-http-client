(ns otel.instrumentation.http-client-test-runner
  (:require [clojure.test :as test]
            [otel.instrumentation.http-client-test]))

(defn -main [& _]
  (let [result (test/run-tests 'otel.instrumentation.http-client-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))

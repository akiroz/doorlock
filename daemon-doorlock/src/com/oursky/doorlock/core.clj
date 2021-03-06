(ns com.oursky.doorlock.core
  (:require [taoensso.timbre :as log]
            [clojure.java.shell :refer [sh]]
            [clojure.core.async :refer [<! >! >!! alts! go-loop chan timeout]]
            [org.httpkit.server :refer [run-server]]
            )
  (:gen-class))

; unlock triggering channel
; identify the trigger source by sending {:source <source>}
(def unlock-chan (chan))

(defn -main [& args]
  ; setup GPIO via wiringpi CLI interface
  (sh "gpio" "mode" "0" "up")
  (sh "gpio" "mode" "1" "out")

  ; listen on unlock-chan for unlock events
  ; if a new unlock event is revieved before the 3000ms timeout, the door is kept open.
  (go-loop [unlock nil]
           (when unlock
             (sh "gpio" "write" "1" "1")
             (loop [[trigger _] [unlock nil]]
               (when trigger
                 (log/info (str "Unlock triggered by " (:source trigger)))
                 (recur (alts! [unlock-chan (timeout 3000)]))))
             (sh "gpio" "write" "1" "0")
             (log/info "Door Locked"))
           (recur (<! unlock-chan)))

  ; button event listener
  ; hold down for atleast 200ms to trigger
  ; will emit event every 25000ms if held down
  (go-loop []
           (if (= 1 (read-string (:out (sh "gpio" "read" "0"))))
             (sh "gpio" "wfi" "0" "falling")
             (<! (timeout 2500)))
           (<! (timeout 200))
           (when (= 0 (read-string (:out (sh "gpio" "read" "0"))))
             (>! unlock-chan {:source :button}))
           (recur))

  ; http event listener
  (run-server (fn [req]
                (>!! unlock-chan {:source (or (get-in req [:headers "x-source"]) :network)})
                {:status 200})
              {:ip "127.0.0.1" :port 8090})

  (log/info "=== Daemon Started ==="))

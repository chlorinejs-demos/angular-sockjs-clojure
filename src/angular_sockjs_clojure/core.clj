(ns angular-sockjs-clojure.core
  (:require [methojure.sockjs.session :refer :all]
            [methojure.sockjs.core :refer :all]
            [compojure.core :refer [GET defroutes]]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.params :refer [wrap-params]]))

(defrecord ChatConnection []
  SockjsConnection
  ;; on open is call whenever a new session is initiated.
  (on-open [this session] session)

  ;; on message is call when a new message arrives at the server.
  (on-message [this session msg]
    (send! session {:type :msg :content msg}))

  ;; when a connection closes this method is called
  (on-close [this session] session))

(defroutes my-routes
  (GET "/" [] "hello world")
  (sockjs-handler "/echo" (->ChatConnection) {:response-limit 4096}))

(defn start-server []
  (run-server (-> my-routes (wrap-params)) {:port 8001}))

(defn -main []
  (start-server)
  (println "Server started."))

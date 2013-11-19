(ns angular-sockjs-clojure.core
  (:require [methojure.sockjs.session :refer :all]
            [cheshire.core :refer [generate-string parse-string]]
            [clojure.string :as str]
            [methojure.sockjs.core :refer :all]
            [compojure.core :refer [GET defroutes]]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.params :refer [wrap-params]]))

(def ^{:doc "Clients are stored in an atom as a hash-map with keys are
  client's id and values are Client records."}
  clients (atom {}))

(defrecord Client
  [name session])

;; Newcomers are supplied with nick names in the form of a string
;; "Guest " followed by an unique number. A counter atom is used.
;; every time a new nick name is supplied, its value will be increased
;; by one.
;; Guests can change their names later.
(def ^{:doc "Counter to append to guest name"}
  guest-name-count (atom 0))

(defn ->camelCase [^String method-name]
  (str/replace method-name #"-(\w)"
               #(str/upper-case (second %1))))

(defn generate-cl2-string
  "Converts Clojure maps to JSON-encoded ChlorineJs-friendly ones
  by camel-casing their keys."
  [data]
  (generate-string
   data
   {:key-fn (fn [k] (->camelCase (name k)))}))

(defn whisper
  "Sends messages to a single client"
  [id msg]
  (when-let [client-session (:session (get @clients id))]
    (send! client-session {:type :msg
                           :content
                           (generate-cl2-string msg)})))

(defn broadcast
  "Sends messages to many clients. An excluded client can be specified"
  [msg & ids-to-ignore]
  (println "Broadcasting " msg ids-to-ignore)
  (let [ignored-ids-set (set ids-to-ignore)]
    (doseq [[id client] @clients
            :when (not (contains? ignored-ids-set id))]
      (send! (:session client) {:type :msg
                                :content (generate-cl2-string msg)}))))

(defn gen-guest-name!
  "Generates a unique guest name for each newcomer."
  []
  (swap! guest-name-count inc)
  (str "Guest " @guest-name-count))

(defn get-users
  "Returns list of current users."
  []
  (->> @clients
       vals
       (map :name)))

(defn id->name
  "Gets a client's name by its id."
  [id]
  (:name (get @clients id)))

(defn available-new-name?
  "Checks if a new user-name has not been used yet."
  [new-name]
  (not (contains? (set (get-users)) new-name)))

(defn on-init
  [client-session]
  (let [id (:id client-session)]
    (println "Oh dear, init from " id)
    (whisper
     id
     {:type "init"
      :name (:name client-session) :users (get-users)})))

(defn on-text
  "Handles text events"
  [data client-session]
  (println "Got some text. Have fun!")
  ;; TODO: strip string?
  (let [id (:id client-session)]
    (broadcast
     {:name (id->name id)
      :message (:message data)
      :type "text"}
     id)))

(defn update-name!
  "Changes client's name with a new one."
  [client-id new-name]
  (swap! clients assoc-in [client-id :name] new-name))

(defn on-change-name
  "Handles on-change-name events"
  [data client-session]
  (println "Change name with " data ", " (:id client-session) "?")
  (let [id (:id client-session)]
    (when (available-new-name? (:name data))
      (let [old-name (id->name id)
            new-name (:name data)]
        (println "Hmm,.." old-name
                 " wants to change their name to " new-name)
        (update-name! id new-name)
        (println "@clients: " @clients)
        (broadcast {:type "change-name"
                    :new-name new-name
                    :old-name old-name})))))

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

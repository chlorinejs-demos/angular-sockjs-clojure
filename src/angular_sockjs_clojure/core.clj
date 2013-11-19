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

(defn on-data
  "Handles messages when an on-data event happens"
  [data client-session]
  ;; TODO: max data size?
  (println "Yummy... got some data" ;;(type data)
           )
  ;; (set! data (deserialize data))
  (println "Good, let's see" (pr-str (:type data)))
  (println "Data: " data)
  (println "Session:" (:id client-session))
  (cond
   (and (= (:type data) "text")
        (seq (:message data)))
   (on-text data client-session)

   (= (:type data) "init")
   (on-init client-session)

   (and (= (:type data) "change-name")
        (seq (:name data)))
   (on-change-name data client-session)))

(defrecord ChatConnection []
  SockjsConnection
  ;; on open is call whenever a new session is initiated.
  (on-open [this client-session]
    (let [id (:id client-session)
          new-name (gen-guest-name!)]
      (println "Fire in the hole!" id)
      (swap! clients
             assoc id (->Client new-name client-session))
      (let [current-users (get-users)]
        (broadcast {:type "new-user" :name new-name
                    :users current-users}
                   id)
        (whisper id
                 {:type "init"
                  :name new-name
                  :users current-users})))
    client-session)

  ;; on message is call when a new message arrives at the server.
  (on-message [this client-session raw-msg]
    (let [data (parse-string raw-msg true)]
      (println "on-message: " data)
      (on-data data client-session))
    client-session)

  ;; when a connection closes this method is called
  (on-close [this client-session]


    (let [id (:id client-session)
          client-name (id->name id)]
      (println "Good bye!" id)
      (swap! clients dissoc id)
      (broadcast {:type "user-left" :name client-name}))
    (println "Current users: " (get-users))
    client-session))

(defroutes my-routes
  (GET "/" [] "hello world")
  (sockjs-handler
   "/chat" (->ChatConnection) {:response-limit 4096}))


(defn start-server []
  (run-server (-> my-routes (wrap-params)) {:port 8001}))

(defn -main []
  (start-server)
  (println "Server started."))

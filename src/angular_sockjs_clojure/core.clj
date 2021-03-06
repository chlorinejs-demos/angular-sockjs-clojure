(ns angular-sockjs-clojure.core
  (:require [methojure.sockjs.session :refer :all]
            [cheshire.core :refer [generate-string parse-string]]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]
            [methojure.sockjs.core :refer :all]
            [compojure.core :refer [GET defroutes]]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.params :refer [wrap-params]])
  (:gen-class))

(defn dev? [args] (some #{"-dev"} args))

(defn port [args]
  (if-let [port (first (remove #{"-dev"} args))]
    (Integer/parseInt port)
    3000))

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
  (timbre/info (str "Broadcasting "  msg
                    (when-let [ids ids-to-ignore]
                      (str " excluding "
                           (str/join ", " ids)))))
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
    (timbre/info "init message from " id)
    (whisper id
     ["init" {:name (:name client-session) :users (get-users)}])))

(defn truncate
  "truncates a string to the given length"
  [^String s limit]
  (apply str (take limit s)))

(defn on-text
  "Handles text events"
  [data client-session]
  (let [id (:id client-session)]
    (timbre/info "text message from" id)
    (broadcast
     ["text" {:name (id->name id)
              :message (truncate (:message data) 140)}]
     id)))

(defn update-name!
  "Changes client's name with a new one."
  [client-id new-name]
  (swap! clients assoc-in [client-id :name] new-name))

(defn on-change-name
  "Handles on-change-name events"
  [data client-session]
  (timbre/info "Change of name request " data
               "from " (:id client-session))
  (let [id (:id client-session)]
    (when (available-new-name? (:name data))
      (let [old-name (id->name id)
            new-name (:name data)]
        (timbre/info old-name
                     " wants to change their name to " new-name)
        (update-name! id new-name)
        (timbre/info "@clients: " @clients)
        (broadcast ["change-name" {:new-name new-name
                                   :old-name old-name}])))))

(defn on-data
  "Handles messages when an on-data event happens"
  [msg-type data client-session]
  ;; TODO: max data size?
  (timbre/info "Data: " data
               " from session of " (:id client-session))
  (cond
   (and (= msg-type "text")
        (seq (:message data)))
   (on-text data client-session)

   (= msg-type "init")
   (on-init client-session)

   (and (= msg-type "change-name")
        (seq (:name data)))
   (on-change-name data client-session)))

(defrecord ChatConnection []
  SockjsConnection
  ;; on open is call whenever a new session is initiated.
  (on-open [this client-session]
    (let [id (:id client-session)
          new-name (gen-guest-name!)]
      (timbre/info "New client connection: " id)
      (swap! clients
             assoc id (->Client new-name client-session))
      (let [current-users (get-users)]
        (broadcast ["new-user" {:name new-name
                                :users current-users}]
                   id)
        (whisper id ["init" {:name new-name
                             :users current-users}])))
    client-session)

  ;; on message is call when a new message arrives at the server.
  (on-message [this client-session raw-msg]
    (let [[msg-type data] (parse-string raw-msg true)]
      (on-data msg-type data client-session))
    client-session)

  ;; when a connection closes this method is called
  (on-close [this client-session]


    (let [id (:id client-session)
          client-name (id->name id)]
      (timbre/info "Good bye, " id "!")
      (swap! clients dissoc id)
      (broadcast ["user-left" {:name client-name}]))
    (timbre/info "Current users: " (get-users))
    client-session))

(defroutes my-routes
  (GET "/" [] "hello world")
  (sockjs-handler
   "/chat" (->ChatConnection) {:response-limit 4096}))

(def app
  (-> my-routes
      (wrap-resource "public")
      (wrap-params)))

(defn -main [& args]
  (run-server
   (if (dev? args) (wrap-reload app) app)
   {:port (port args)})
  (timbre/info "server started on port" (port args)))

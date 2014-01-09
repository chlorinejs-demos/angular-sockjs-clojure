(load-file "angular-cl2/src/angular.cl2")

(def sockjs-url (+* window.location.protocol "//"
                    window.location.host
                    "/chat"))

(defn serialize
  "Converts a message map to a JSON string so that it can be transfered
over the network."
  [msg]
  (. JSON (stringify msg)))

(defn deserialize
  "Converts a serialized message back to a map"
  [data]
  (. JSON (parse data)))

(defn scope-data-handler
  "Receives some data (and scope) from sockjs, do some transformations
   to the scope."
  [data $scope]
  (console.log "Got this msg: " data)
  (case data.type
    "init"
    (do
      (console.log "initializing... Go!")
      (def$ name  data.name)
      (def$ users data.users))

    "text"
    (.. $scope
        -messages
        (push {:text data.message, :user data.name}))

    "change-name"
    (scope-change-name (:old-name data) (:new-name data)
                 $scope)

    "new-user"
    (do
      (..
       $scope
       -messages
       (push
        {:text (+ "User " data.name " has joined."),
         :user "chatroom"}))
      (.. $scope -users (push data.name)))

    "user-left"
    (do
      (..
       $scope
       -messages
       (push
        {:text (+ "User " data.name " has left."),
         :user "chatroom"}))
      (def$ users
        (remove #(= data.name %)
                $scope.users))))
  ;; enforce this function to return nothing
  nil)

(defn scope-change-name
  "Updates scope when someone changes his/her name. Helper function of
  `scope-data-handler`."
  [old-name new-name $scope]
  (console.log "Before, users: " $scope.users)
  (console.log "and old name is: " old-name)
  (def$ users
    (conj (filter #(not= % old-name) $scope.users)
          new-name))
  (console.log "After, users: " $scope.users)
  (if (= old-name $scope.name)
    (def$ name new-name))
  (..
   $scope
   -messages
   (push
    {:text (+* "User " old-name " is now known as " new-name),
     :user "chatroom"})))

(defapp myApp [])

(defcontroller AppCtrl
  [$scope socket]
  ;; Messages are stored in a vector. Each message is a map with
  ;; the form of {:user "the-user-who-sent-the-msg" :text "msg-content"}.
  ;; ;user can be `chatroom` (aka the server)
  (def$ messages [])

  (defn$ changeName
    "When a user changes his own name, sends that to server via sockjs.
  If server responds true which means the new name was accepted,
  clears `newName` box, otherwise alerts the user."
    []
    (if (.. socket (emit {:type "change-name"
                          :name $scope.newName}))
      (def$ newName "")
      (alert "There was an error changing your name")))

  (defn$ sendMessage
    "Does some tasks when a user clicks to send his message away:
  - sends the message to server via sockjs
  - adds that message to global messages (hence the `Messages` log gets
  updated)
  - clears the `Message` box"
    []
    (. socket (emit {:type "text"
                     :message $scope.message}))
    (.. $scope
        -messages
        (push {:text $scope.message, :user $scope.name}))
    (def$ message ""))

  (defn socket.onmessage
    "Adds `onmessage` method to sockjs instance by using `scope-data-handler`
  helper function. Because sockjs is outside of scope, `$scope.$apply`
 is needed to update things."
    [message]
    ($scope.$apply
     (fn [] (scope-data-handler (deserialize message.data) $scope)))))

(defservice socket
  [$rootScope]
  (def sock (SockJS. sockjs-url nil
                     #_{:protocols_whitelist
                        ['xhr-polling]}))
  ;; Adds some basic methods: `onopen` and `emit` to the new sockjs instance
  (defn sock.onopen []
    (console.log "Connected"))
  (defn sock.emit [data]
    (. sock send (serialize data)))
  ;; returns the modified sockjs instance
  sock)

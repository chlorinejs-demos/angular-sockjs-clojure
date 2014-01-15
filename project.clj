(defproject angular-sockjs-clojure "0.1.0-SNAPSHOT"
  :description "A chat application written in Clojure and AngularJS"
  :url "http://github.com/chlorinejs-demos/angular-sockjs-clojure/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [http-kit-sockjs "0.2.0-SNAPSHOT"]
                 [lib-noir "0.7.5"]
                 [com.taoensso/timbre "2.7.1"]
                 [ring/ring-devel "1.1.8"]]
  :main angular-sockjs-clojure.core
  :aot :all
  :plugins [[lein-cl2c "0.0.1-SNAPSHOT"]
            [lein-bower "0.2.0-SNAPSHOT"]]
  :node-dependencies [[angular-cl2 "0.3.3-SNAPSHOT"]
                      [socket-cl2 "0.2.0"]]
  :bower {:directory "resources/public/js/lib"}
  :bower-dependencies [[sockjs "~0.3.4"]
                       [angular "~1.0.6"]]
  :cl2c {:client
         { ;; where to check for changes?
          :watch ["src-cl2", "test-cl2", "node_modules"]
          ;; sometimes we don't want to compile all found files
          ;; but only some of them. Patterns can be combined together
          ;; with `and` and `or` to make complex filter.
          :filter (or "src-cl2/" "test-cl2/")
          ;; - How the compiler figure out its output files from given input?
          ;; - It's surely a rule
          :path-map ["src-cl2/" => "resources/public/js/"]
          ;; where to find cl2 sources?
          :paths ["node_modules/"]
          ;; what strategy to use?
          :strategy "prod"
          ;; some files may take too long to compile. We need a limit
          :timeout 2000
          ;; how are output files optimized?
          ;; available options: :advanced, :simple (default) and :pretty
          :optimizations :pretty}}
  :min-lein-version "2.0.0")

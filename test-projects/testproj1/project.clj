(defproject testproj1 "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]]
  :plugins [[cljsee "0.1.0-SNAPSHOT"]]
  :source-paths ["src/cljc"]
  :test-paths ["test/cljc"]
  :cljsee {:builds [{:source-paths ["src/cljc"]
                     :output-path "target/classes"
                     :rules :clj}
                    {:source-paths ["src/cljc"]
                     :output-path "target/classes"
                     :rules :cljs}]}
  :profiles {:test {:cljsee {:builds
                             [{:source-paths ["test/cljc"]
                               :output-path "target/classes"
                               :rules :clj}
                              {:source-paths ["test/cljc"]
                               :output-path "target/classes"
                               :rules :cljs}]}}
             :old-clojure {:dependencies [[org.clojure/clojure "1.6.0"]]}}
  :prep-tasks [["cljsee" "once"]])

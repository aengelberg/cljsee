(ns leiningen.cljsee
  (:require cljsee.core
            [watchtower.core :as wt]))

(def no-opts-warning "You need a :cljsee entry in your project.clj! See the cljsee docs for more info.")

(defn- once
  "Transform .cljc files once and then exit."
  [project builds]
  (cljsee.core/cljsee-compile builds))

(defn- auto
  "Watch .cljc files and transform them after any changes."
  [project builds]
  (let [dirs (set (flatten (map :source-paths builds)))]
    (println "Watching" (vec dirs) "for changes.")
    (-> (wt/watcher* dirs)
        (wt/file-filter (wt/extensions :cljc))
        (wt/rate 250)
        (wt/on-change (fn [files]
                        (cljsee.core/cljsee-compile builds :files files)))
        (wt/watch))))

(defn cljsee
  "Statically transform .cljc files into Clojure and ClojureScript sources."
  {:subtasks [#'once #'auto]}
  ([project] (cljsee project "once"))
  ([project subtask]
   (if-let [opts (:cljsee project)]
     (if-let [{builds :builds} opts]
       (case subtask
         "once" (once project builds)
         "auto" (auto project builds)))
     (println no-opts-warning))))

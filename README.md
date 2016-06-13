# cljsee

Cljsee is a Leiningen plugin that aims to help library authors to write portable code in the `.cljc` format that can be used by projects that run Clojure 1.6 or below. It accomplishes this by parsing the cljc conditionals as a compile-time step and producing `.clj` and `.cljs` files with only the relevant code.

Cljsee performs a very similar role to the now-deprecated [cljx](https://github.com/lynaghk/cljx) plugin (and I've intentionally modeled cljsee's build configuration after cljx's syntax to make it easy to switch over). But users of cljsee have access to the modern reader conditionals that are official to the Clojure language, including the splicing (`#?@(...)`) conditional, instead of the `#+clj / #+cljs` syntax specific to the cljx plugin. 

Unlike cljx, cljsee does not include nREPL middleware, because as long as your interactive development is done in Clojure 1.7 or above, reader conditionals will properly load at any REPL.

## Usage

In your `project.clj`, include cljsee as a dependency, then add a cljsee build configuration.

```clojure
(defproject my-cool-backwards-compatible-project
  ...
  :plugins [[cljsee "0.1.0-SNAPSHOT"]]
  :cljsee {:builds [{:source-paths ["src/"]
                     :output-path "target/classes"
                     :rules :clj}
                    {:source-paths ["src/"]
                     :output-path "target/generated/cljs"
                     :rules :cljs}]})
```

To compile cljc source code once:
```
$ lein cljsee once
```

To start a watcher that automatically cross-compiles when files are changed:
```
$ lein cljsee auto
```

It's encouraged to add cljsee as a "prep task" so that your cljc files get backported before critical tasks are performed such as jarring or deploying.

```clojure
:prep-tasks [["cljx" "once"]]
```

Note that even though the `cljc` format is no longer in the way of Clojure backwards-compatibility, there are other factors (such as missing features or unfixed bugs) to be careful of when supporting older versions of Clojure. To test that your library works on Clojure 1.6.0, for instance, you can add cljsee builds to your test namespaces like so:

```clojure
:profiles {:old-clojure {:dependencies [[org.clojure/clojure "1.6.0"]]}
           :test {:cljsee {:builds
                           [{:source-paths ["test/cljc"]
                             :output-path "target/classes"
                             :rules :clj}
                            {:source-paths ["test/cljc"]
                             :output-path "target/classes"
                             :rules :cljs}]}}}
```

To then run your test cases on Clojure 1.6:

```
$ lein with-profile old-clojure test
```

## Behind the scenes

Given the following cljc file:
```clojure
;; my/ns.cljc
(ns my.ns
  (:require [my.helper.ns
             #?(:clj :refer
                :cljs :refer-macros)
             [my-macro]])
  #?(:clj (:import my.class)))
```
The following Clojure source is generated:
```clojure
;; my/ns.clj
(ns my.ns
  (:require [my.helper.ns
                     :refer
                                   
             [my-macro]])
          (:import my.class) )
```
The irrelevant code is "whited out" in the target files. This strategy preserves
the line-column placement of the remaining code, so if errors arise, you can track them down in
your original cljc file.

## Credits

Alex Engelberg is the author of cljsee. Issues and pull requests are welcome.

Some functions were copied from Kevin Lynagh's cljx library.

## License

Copyright © 2016 Alex Engelberg

Distributed under the Eclipse Public License, the same as Clojure.

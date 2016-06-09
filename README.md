Ever since cljx became [deprecated](https://github.com/lynaghk/cljx/commit/48c8f912db9fa7ac363e346664b98c7d64bf6e3b), there has been no (non-deprecated) way to write portable code that is compatible with Clojure 1.6 and below.

# cljsee

Cljsee is a plugin that generates `.clj` and `.cljs` files, similar to cljx, but uses the `.cljc` reader conditional format.

## Usage

Cljsee aims to be similar if not identical to cljx in its `project.clj` syntax:

```clojure
(defproject my-cool-backwards-compatible-project
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
Just like in cljx, the irrelevant code is "whited out" in the target files. This strategy preserves
the line-column placement of the remaining code, so if errors arise, you can track them down in
your original cljc file.

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
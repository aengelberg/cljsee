Once upon a time in the days of Clojure 1.6, [cljx](https://github.com/lynaghk/cljx) was a popular
plugin that allowed one to write code in a single file that emits both Clojure and ClojureScript
code.

In Clojure 1.7, [reader conditionals](http://dev.clojure.org/display/design/Reader+Conditionals)
were introduced, which introduce `.cljc` files that are inspired by `.cljx` files but have slightly
different syntax. Also, `.cljc` files
are read by Clojure at runtime, as opposed to `.cljx` files which are split into `.clj`
and `.cljs` files as a compile step.

cljx is now [deprecated](https://github.com/lynaghk/cljx/commit/48c8f912db9fa7ac363e346664b98c7d64bf6e3b).
This is understandable, as it feels silly to have both
options available when one is considered "official". However, there is a problem. `.cljc` files
do not work on Clojure 1.6 or below. They are simply ignored by older compilers. Cljx did not have
this problem, as it could generate `clj` and `cljs` files that could be read by any version of
Clojure or ClojureScript (so long as the functions and macros used in your source code
were also compatible with the targeted Clojure version).

Thus, there is no option for easily writing portable code that is backwards compatible with older
versions of Clojure. Until now.

# cljsee

Cljsee is a plugin that takes us back to an age where Clojure and ClojureScript source was
generated at compile-time. But it uses the modern, now more familiar `.cljc` reader conditional
format.

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

Cljx users can likely guess the command-line syntax to trigger cljsee builds.

To compile cljc source code once:
```
$ lein cljsee once
```

To start a watcher that automatically cross-compiles when files are changed:
```
$ lein cljsee auto
```

See the [cljx installation section](https://github.com/lynaghk/cljx#installation) to set up
cljsee as a "prep task" to be executed before compilation / release.

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

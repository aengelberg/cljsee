(ns testproj1.core)

#?(:clj
   (defn foo
     "I don't do a whole lot."
     [x]
     (println x "Hello, World!"))
   :cljs
   (defn foo
     "I don't do a whole lot on cljs."
     [x]
     (println x "Hi world!")))

(ns cljsee.core-test
  (:require [clojure.test :refer :all]
            [cljsee.parse :refer [process-source-code]]))

(deftest test-parser
  (are [x features y] (= y (process-source-code x features))
    "#?(:a 1 :b 2)" #{:a}
    "      1      "

    "#?(:a 1)" #{:a}
    "      1 "

    "#?(:a 1)" #{:b}
    "        "

    "#?(:a 1) #?(:b 2)" #{:a :b}
    "      1        2 "

    "#?()" #{:a}
    "    "

    "#?(:a 1 :b 2 :default 3)" #{:c}
    "                      3 "

    "#?(:a 1 :default 2)" #{:a :b}
    "      1            "

    "#?(:a 1 :default 2)" #{:a}
    "      1            "

    "#?(:a #?(:b 1))" #{:a :b}
    "            1  "

    "[#?@(:a [1 2] :b [3 4])]" #{:a}
    "[        1 2           ]"

    "[#?@(:a [1 2 3])]" #{:a}
    "[        1 2 3  ]"

    "[#?@(:a [1 2 3])]" #{:b}
    "[               ]"

    "[#?@(:a [])]" #{:a}
    "[          ]"

    "[#?@(:a [1 2] :default [3 4])]" #{:a}
    "[        1 2                 ]"

    "[#?@(:a [1 2] :default [3 4])]" #{:B}
    "[                       3 4  ]"

    "#{#?(:a 1)}" #{:a}
    "#{      1 }"

    "#{#?@(:a [1 2])}" #{:a}
    "#{        1 2  }"

    "{#?@(:a [1]) #?@(:a [2 3 4])}" #{:a}
    "{        1           2 3 4  }"

    ;; #= shouldn't evaluate during a parse. (note that this usage of
    ;; #= would be problematic in regular cljc code)
    "#?(:a #=(/ 1 0) :b #=(/ 1 0))" #{:a}
    "      #=(/ 1 0)              "

    ;; same if we switch it around
    "#=(/ #?@(:a [1 0]))" #{:a}
    "#=(/         1 0  )"

    ;; test keywords
    "#?(:a :simple-keyword)" #{:a} "      :simple-keyword "
    "#?(:a :namespaced/keyword)" #{:a} "      :namespaced/keyword "
    "#?(:a ::alias/keyword)" #{:a} "      ::alias/keyword "
    "#?(:a :simple-keyword)" #{:B} "                      "
    "#?(:a :namespaced/keyword)" #{:B} "                          "
    "#?(:a ::alias/keyword)" #{:B} "                      "
    ":simple-keyword" #{:a} ":simple-keyword"
    ":namespaced/keyword" #{:a} ":namespaced/keyword"
    "::alias/keyword" #{:a} "::alias/keyword"
    ":2pac" {} ":2pac"
    ":2p/ac" {} ":2p/ac"
    "::2p/ac" {} "::2p/ac")

  ;; ensure comments don't prevent the readcond from parsing, but we
  ;; don't guarantee whether they end up in the result of the parse.
  (is (= 'result (read-string
                  (process-source-code
                   "#?(#_comment ;another comment
                       #_#_ more comments
                       :a ; not quite yet
                       #_#_#_ here it comes ;; wait for it
                       result ; <-- there it is!
                       #_done ) ; bye"
                   #{:a})))))

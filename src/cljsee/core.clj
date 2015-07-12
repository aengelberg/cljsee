(ns cljsee.core
  (:require [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as t]
            [clojure.tools.reader.impl.utils :refer [whitespace?]]
            [clojure.tools.reader.impl.commons :refer [read-past]]))

(defrecord WrappedExpr [form])

(defrecord ReadEval [form])

(defn starting-line-col-info-double
  "Decreases the starting line/col by 2 instead of 1."
  [rdr]
  (when (t/indexing-reader? rdr)
    [(t/get-line-number rdr) (int (- (t/get-column-number rdr) 2))]))

(defn starting-line-col-info-zero
  "Does not decrease the starting line/col, instead of by 1."
  [rdr]
  (when (t/indexing-reader? rdr)
    [(t/get-line-number rdr) (t/get-column-number rdr)]))

(defn disabled-read-eval
  "Evaluate a reader literal"
  [rdr _ opts pending-forms]
  (let [form (#'rdr/read* rdr true nil opts pending-forms)]
    (->ReadEval form)))

(defn- read-list-with-wrappers
  [rdr _ opts pending-forms]
  (let [[start-line start-column] (#'rdr/starting-line-col-info rdr)]
    (binding [rdr/*read-delim* true]
      (loop [a (transient [])]
        (let [[form-start-line form-start-column] (starting-line-col-info-zero rdr)
              form (#'rdr/read* rdr false @#'rdr/READ_EOF \) opts pending-forms)
              [form-end-line form-end-column] (#'rdr/ending-line-col-info rdr)]
          (if (identical? form @#'rdr/READ_FINISHED)
            (let [[end-line end-column] (#'rdr/ending-line-col-info rdr)
                  the-list (persistent! a)]
              (with-meta (if (empty? the-list)
                           '()
                           (clojure.lang.PersistentList/create the-list))
                {:line start-line
                 :column start-column
                 :end-line end-line
                 :end-column end-column}))
            (if (identical? form @#'rdr/READ_EOF)
              (t/reader-error rdr "EOF while reading"
                              (when start-line
                                (str ", starting at line " start-line
                                     " and column " start-column)))
              (let [form (with-meta (->WrappedExpr form)
                           {:line form-start-line
                            :column form-start-column
                            :end-line form-end-line
                            :end-column form-end-column})]
                (recur (conj! a form))))))))))

(defn- read-cond-with-wrappers
  "Returns a (WrappedExpr. (reader-conditional [(WrappedExpr. x) (WrappedExpr. y) ...] splicing))
  with line-col metadata on each WrappedExpr. Note: the inner wrappers may have start-line/start-col
  information that includes leading whitespace, but that will not matter for our purposes."
  [rdr _ opts pending-forms]
  (let [[rc-start-line rc-start-column] (starting-line-col-info-double rdr)]
    (if-let [ch (t/read-char rdr)]
      (let [splicing (= ch \@)
            ch (if splicing (t/read-char rdr) ch)]
        (when splicing
          (when-not @#'rdr/*read-delim*
            (t/reader-error rdr "cond-splice not in list")))
        (if-let [ch (if (whitespace? ch) (read-past whitespace? rdr) ch)]
          (if (not= ch \()
            (throw (RuntimeException. "read-cond body must be a list"))
            (binding [rdr/*suppress-read* true]
              (let [form (read-list-with-wrappers rdr ch opts pending-forms)
                    [rc-end-line rc-end-column] (#'rdr/ending-line-col-info rdr)]
                (with-meta
                  (->WrappedExpr (reader-conditional form splicing))
                  {:line rc-start-line
                   :column rc-start-column
                   :end-line rc-end-line
                   :end-column rc-end-column}))))
          (t/reader-error rdr "EOF while reading character")))
      (t/reader-error rdr "EOF while reading character"))))

(defn modified-read-string
  [s]
  (with-redefs [rdr/read-eval disabled-read-eval
                rdr/read-cond read-cond-with-wrappers]
    (rdr/read {:read-cond :preserve} (t/indexing-push-back-reader s))))

(defn find-read-conds
  [form]
  (let [read-conds (atom ())]
    (clojure.walk/prewalk (fn [form]
                            (when (reader-conditional? form)
                              (swap! read-conds conj form))
                            (if (reader-conditional? form)
                              (:form form) ; extract the form so we can continue walking
                              form))
                          form)
    @read-conds))

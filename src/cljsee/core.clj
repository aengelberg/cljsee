(ns cljsee.core
  (:require [clojure.string :as str]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.impl.commons :refer [read-past]]
            [clojure.tools.reader.impl.utils :refer [whitespace?]]
            [clojure.tools.reader.reader-types :as t]))

(defn read-while
  "Like read-past, but unreads the last char which doesn't satisfy the predicate."
  [pred rdr]
  (let [ch (read-past pred rdr)]
    (t/unread rdr ch)))

(defrecord MetaWrapper [expr])

(defn wrapped-expr? [x]
  (instance? MetaWrapper x))

(defn unwrap [x]
  (if (wrapped-expr? x)
    (:expr x)
    x))

(defn add-meta
  "Like with-meta, but will wrap an object with WrappedExpr if it does not already implement IObj."
  [obj m]
  (if (instance? clojure.lang.IObj obj)
    (with-meta obj m)
    (recur (->WrappedExpr obj) m)))

(defrecord ReadEval [expr])

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
        (read-while whitespace? rdr)
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
              (let [form (add-meta form
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
                (add-meta
                 (reader-conditional form splicing)
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
  "Returns all of wrapped reader-conditional constructs within a form."
  [form]
  (let [read-conds (atom ())]
    (clojure.walk/prewalk (fn [form]
                            (if (and (wrapped-expr? form)
                                     (reader-conditional? (unwrap form)))
                              (do (swap! read-conds conj form)
                                  (:form (unwrap form))) ; extract the sub-list so we can continue
                              form))
                          form)
    @read-conds))

(defn curry-line-col->index
  "Takes a string, and returns a function that takes a line and col and returns a character index."
  [str]
  (let [lines (re-seq #".*\r?\n?" str)
        _ (println lines)
        line-num->index (loop [lines lines
                               line-num 1
                               num-chars-so-far 0
                               table {}]
                          (if (empty? lines)
                            table
                            (recur (rest lines)
                                   (inc line-num)
                                   (+ num-chars-so-far (count (first lines)))
                                   (assoc table line-num num-chars-so-far))))]
    (fn [line col]
      (+ (line-num->index line) (dec col)))))

(defn white-out-str!
  "\"whites out\" the given range in the text, i.e. converts all non-space characters to spaces
  (preserving other whitespace characters, including newlines)."
  [^StringBuilder text start end]
  (.replace text start end (str/replace (.subSequence text start end) #"\S" " ")))

(defn pick-form-from-read-cond!
  [^StringBuilder text wrapped-rc features lc->i]
  (let [{rc-start-line :line
         rc-end-line :end-line
         rc-start-column :column
         rc-end-column :end-column} (meta wrapped-rc)
         rc (unwrap wrapped-rc)
         the-list (:form rc)
         form-to-keep (first (for [[wrapped-key wrapped-val] (partition 2 the-list)
                                   :when (contains? features (unwrap wrapped-key))]
                               wrapped-val))
         {form-start-line :line
          form-end-line :end-line
          form-start-column :column
          form-end-column :end-column} (meta form-to-keep)]
    (if-not (:splicing rc)
      ;; read cond NOT splicing #?(...)
      (doto text
        (white-out-str! (lc->i rc-start-line rc-start-column)
                        (lc->i form-start-line form-start-column))
        (white-out-str! (lc->i form-end-line form-end-column)
                        (lc->i rc-end-line rc-end-column)))
      ;; read cond splicing #?@(...)
      )))

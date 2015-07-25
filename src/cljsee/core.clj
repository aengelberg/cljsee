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

(defrecord ReadCond [splicing form lc-metas]) ; stores a form (:clj 1 :cljs 2 ...) and a list of
                                              ; line-column metadata for each element within.

(defn special-read-cond
  [rdr _ opts pending-forms]
  (let [[rc-start-line rc-start-column] (starting-line-col-info-double rdr)
        ch (t/read-char rdr)
        _ (when-not ch
            (t/reader-error rdr "EOF while reading character"))
        splicing (= ch \@)
        ch (if splicing (t/read-char rdr) ch)
        _ (when (and splicing (not @#'rdr/*read-delim*))
            (t/reader-error rdr "cond-splice not in list"))
        ch (if (whitespace? ch) (read-past whitespace? rdr) ch)
        _ (when-not ch
            (t/reader-error rdr "EOF while reading character"))
        _ (when (not= ch \()
            (throw (RuntimeException. "read-cond body must be a list")))]
    (binding [rdr/*suppress-read* true
              rdr/*read-delim* true]
      (loop [forms []
             lc-metas []]
        (read-while whitespace? rdr)
        (let [[form-start-line form-start-column] (starting-line-col-info-zero rdr)
              form (#'rdr/read* rdr false @#'rdr/READ_EOF \) opts pending-forms)
              [form-end-line form-end-column] (#'rdr/ending-line-col-info rdr)]
          (if (identical? form @#'rdr/READ_FINISHED)
            (let [[rc-end-line rc-end-column] (#'rdr/ending-line-col-info rdr)]
              (with-meta
                (->ReadCond splicing
                            forms
                            lc-metas)
                {:line rc-start-line
                 :column rc-start-column
                 :end-line rc-end-line
                 :end-column rc-end-column}))
            (if (identical? form @#'rdr/READ_EOF)
              (t/reader-error rdr "EOF while reading"
                              (when rc-start-line
                                (str ", starting at line " rc-start-line
                                     " and column " rc-start-column)))
              (let [lc-meta {:line form-start-line
                             :column form-start-column
                             :end-line form-end-line
                             :end-column form-end-column}]
                (recur (conj forms form)
                       (conj lc-metas lc-meta))))))))))

(defn modified-read-string
  [s]
  (with-redefs [rdr/read-eval disabled-read-eval
                rdr/read-cond special-read-cond]
    (rdr/read {:read-cond :preserve} (t/indexing-push-back-reader s))))

(defn find-read-conds
  "Returns all of wrapped reader-conditional constructs within a form."
  [form]
  (let [read-conds (atom ())]
    (clojure.walk/prewalk (fn [form]
                            (when (instance? ReadCond form)
                              (swap! read-conds conj form))
                            form)
                          form)
    @read-conds))

(defn curry-line-col->index
  "Takes a string, and returns a function that takes a line and col and returns a character index."
  [str]
  (let [lines (re-seq #".*\r?\n?" str)
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
  [^StringBuilder text rc features lc->i]
  (let [{rc-start-line :line
         rc-end-line :end-line
         rc-start-column :column
         rc-end-column :end-column} (meta rc)
         the-list (:form rc)
         available-features (map first (partition 2 the-list))
         target-feature (or (some features available-features) :default)
         range-to-keep (first (for [[[k v] [_ m]] (map vector
                                                       (partition 2 the-list)
                                                       (partition 2 (:lc-metas rc)))
                                    :when (= k target-feature)]
                                m))
         {form-start-line :line
          form-end-line :end-line
          form-start-column :column
          form-end-column :end-column} range-to-keep]
    (cond
      (not range-to-keep)
      (doto text
        (white-out-str! (lc->i rc-start-line rc-start-column)
                        (lc->i rc-end-line rc-end-column)))

      (not (:splicing rc))
      ;; read cond NOT splicing #?(...)
      (doto text
        (white-out-str! (lc->i rc-start-line rc-start-column)
                        (lc->i form-start-line form-start-column))
        (white-out-str! (lc->i form-end-line form-end-column)
                        (lc->i rc-end-line rc-end-column)))

      :else
      ;; read cond splicing #?@(...)
      (doto text
        (white-out-str! (lc->i rc-start-line rc-start-column)
                        (inc (lc->i form-start-line form-start-column)))
        (white-out-str! (dec (lc->i form-end-line form-end-column))
                        (lc->i rc-end-line rc-end-column))))))

(defn process-source-code
  [code features]
  (let [sb (StringBuilder. code)
        lc->i (curry-line-col->index code)
        data (modified-read-string code)
        readconds (find-read-conds data)]
    (doseq [rc readconds]
      (pick-form-from-read-cond! sb rc features lc->i))
    (str sb)))

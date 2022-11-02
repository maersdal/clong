(ns com.phronemophobic.clong.gen.jna
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [insn.core :as insn]
            [clojure.pprint :refer [pprint]]
            [insn.util :as insn-util]
            [no.disassemble.r :as r]
            [clojure.edn :as edn])
  (:import java.io.PushbackReader
           com.sun.jna.Memory
           com.sun.jna.Pointer
           com.sun.jna.Platform
           com.sun.jna.ptr.FloatByReference
           com.sun.jna.ptr.IntByReference
           com.sun.jna.IntegerType
           com.sun.jna.Structure$ByValue
           com.sun.jna.Structure
           com.sun.jna.Structure$FieldOrder
           com.sun.jna.Callback
           java.util.List))

(def ^:no-doc main-class-loader @clojure.lang.Compiler/LOADER)
(def ^:no-doc void Void/TYPE)

;; (defmacro ^:no-doc defc
;;   ([fn-name lib ret]
;;    `(defc ~fn-name ~lib ~ret []))
;;   ([fn-name lib ret args]
;;    (let [cfn-sym (with-meta (gensym "cfn") {:tag 'com.sun.jna.Function})]
;;      `(let [~cfn-sym (delay (.getFunction ~(with-meta `(deref ~lib) {:tag 'com.sun.jna.NativeLibrary})
;;                                           ~(name fn-name)))]
;;         (defn- ~fn-name [~@args]
;;           (.invoke (deref ~cfn-sym)
;;                    ~ret (to-array [~@args])))))))

;; ;; (defc dispatch_sync_f objlib void [queue context work])

(defn coffi-type->insn-type [t]
  (case t
    :coffi.mem/char :byte
    :coffi.mem/short :short
    :coffi.mem/int :int
    :coffi.mem/long :long
    :coffi.mem/float :float    
    :coffi.mem/double :double
    :coffi.mem/pointer Pointer
    :coffi.mem/void :void

    (cond
      (vector? t)
      (case (first t)
        :coffi.mem/pointer Pointer

        :coffi.ffi/fn com.sun.jna.Callback
        
        :coffi.mem/array
        [(coffi-type->insn-type (second t))])
      

      (keyword? t)
      (if (= "coffi.mem" (namespace t))
        (throw (ex-info "Unknown coffi type."
                        {:t t}))
        ;;else
        (reify
          insn-util/ClassDesc
          (class-desc [_]
            (str "com/phronemophobic/clong/struct/" (name t)))
          insn-util/TypeDesc
          (type-desc [_]
            (str "Lcom/phronemophobic/clong/struct/" (name t) ";")))))))

(defn coffi-type->jna [t]
  (case t
    :coffi.mem/char Byte/TYPE
    :coffi.mem/short Short/TYPE
    :coffi.mem/int Integer/TYPE
    :coffi.mem/long Long/TYPE
    :coffi.mem/float Float/TYPE    
    :coffi.mem/double Double/TYPE
    :coffi.mem/pointer Pointer
    :coffi.mem/void void
    
    (cond
      (= [:coffi.mem/pointer :coffi.mem/char]
         t)
      String

      (vector? t)
      (case (first t)
        :coffi.mem/pointer Pointer

        :coffi.ffi/fn com.sun.jna.Callback
        
        :coffi.mem/array
        (Class/forName (insn-util/type-desc [(coffi-type->insn-type (second t))])))

      (keyword? t)
      (if (= "coffi.mem" (namespace t))
        (throw (ex-info "Unknown coffi type."
                        {:t t}))
        ;;else
        (Class/forName
         (str "com.phronemophobic.clong.struct." (name t)))))))

(defn coffi-type->initial-value [t]
  (when (and (vector? t)
             (= :coffi.mem/array (first t)))
    (let [[_ type size] t]
      (case type
        :coffi.mem/char (byte-array size)
        :coffi.mem/short (short-array size)
        :coffi.mem/int (int-array size)
        :coffi.mem/long (long-array size)
        :coffi.mem/float (float-array size)
        :coffi.mem/double (double-array size)
        :coffi.mem/pointer (object-array size)))))

(defonce ^:no-doc not-garbage
  (atom []))

(defn ^:no-doc preserve!
  "Store this value so it's not garbage collected"
  [x]
  (swap! not-garbage conj x)
  x)

(defn struct->class-def [struct]
  (let [fields (:fields struct)]
    {:name (symbol (str "com.phronemophobic.clong.struct." (name (:id struct))))
     :super Structure
     :interfaces [Structure$ByValue]
     :flags #{:public}
     :fields (into []
                   (map (fn [field]
                          (let [type (coffi-type->insn-type (:datatype field))]
                            (merge
                             {:flags #{:public}
                              :name (:name field)
                              ;; :type2 (insn-util/type-desc type)
                              :type type}))))
                   fields)
     :methods
     [{:name :init
       :emit (vec
              (concat
               [[:aload 0]
                [:invokespecial :super :init [:void]]]

               (eduction
                (filter (fn [{t :datatype}]
                          (and (vector? t)
                               (= :coffi.mem/array (first t)))))
                (mapcat (fn [{:keys [datatype name]}]
                          (let [[_ t size] datatype
                                array-type (coffi-type->insn-type t)]
                            [[:aload 0]
                             [:ldc size]
                             
                             (if (insn-util/array-type-keyword? array-type )
                               [:newarray array-type]
                               [:anewarray array-type])
                             [:putfield :this name (coffi-type->insn-type datatype)]
                             ])))
                fields)


               [[:return]]))}]
     
     :annotations {com.sun.jna.Structure$FieldOrder
                   (mapv :name fields)}}))

(defn def-struct [struct]
  (let [class-data (struct->class-def struct)
        klass (insn/define class-data)]
    klass))

;; (run! def-struct (:structs clang-api))

(defn make-callback-interface* [ret-type arg-types]
  {:flags #{:public :interface}
   :interfaces [Callback] 
   :methods [{:flags #{:public :abstract}
              :name :callback
              :desc
              (conj (mapv coffi-type->insn-type arg-types)
                    (coffi-type->insn-type ret-type))}]})

(defn make-callback-interface [ret-type arg-types]
  (insn/define (make-callback-interface* ret-type arg-types)))
(def make-callback-interface-memo (memoize make-callback-interface))

(def my-atom (atom false))
(defn callback-maker* [ret-type arg-types]
  (let [interface (make-callback-interface-memo ret-type arg-types)
        args (map (fn [i] (symbol (str "x" i)))
                  (range (count arg-types)))]
    `(fn [f#]
       (preserve! f#)
       (preserve!
        (reify
          ~(symbol (.getName interface))
          (~'callback [this# ~@args]
           (.setContextClassLoader (Thread/currentThread) main-class-loader)
           (reset! my-atom true)
           (f# ~@args)))))))

(defn callback-maker [ret-type arg-types]
  (eval (callback-maker* ret-type arg-types)))

(defn array? [o]
  (let [c (class o)]
    (.isArray c)))

(defn coercer [t]
  (case t
    :coffi.mem/char byte
    :coffi.mem/short short
    :coffi.mem/int int
    :coffi.mem/long long
    :coffi.mem/float float    
    :coffi.mem/double double
    :coffi.mem/pointer (fn [o]
                         (when-not (or (nil? o)
                                       (string? o)
                                       (array? o)
                                       (instance? Pointer o))
                           (throw (ex-info "Must be a pointer"
                                           {:o o})))
                         o)

    (cond
      (vector? t)
      (case (first t)
        :coffi.mem/pointer (coercer :coffi.mem/pointer)

        :coffi.ffi/fn
        (let [->callback
              (callback-maker (nth t 2) (nth t 1))]
          
          (fn [o]
            (if (instance? Callback o)
              o
              (->callback o))))
        
        :coffi.mem/array
        (fn [o]
          (into-array (class o)
                      o)))
      

      (keyword? t)
      (if (= "coffi.mem" (namespace t))
        (throw (ex-info "Unknown coffi type."
                        {:t t}))
        ;;else
        (let [cls (Class/forName (str "com.phronemophobic.clong.struct." (name t)))]
          (fn [o]
            (when-not (instance? cls o)
              (throw (ex-info (str "Must be a " "com.phronemophobic.clong.struct." (name t))
                              {:o o})))
            o))))))

(defn def-fn* [f]
  (let [args (mapv (fn [arg]
                     (let [arg-name (:spelling arg)]
                       (symbol
                        (if (= arg-name "")
                          (str/replace (:type arg)
                                       #" "
                                       "_")
                          arg-name))))
                   (:args f))
        cfn-sym (with-meta (gensym "cfn") {:tag 'com.sun.jna.Function})
        fn-name (symbol (:symbol f))
        lib## (gensym "lib_")
]
    `(fn [~lib##]
       (let [ret-type# (coffi-type->jna
                        ~(:function/ret f))
             coercers#
             (doall (map coercer ~(:function/args f)))]
         (defn ~fn-name
           ~(let [doc (:raw-comment f)]
              (str
               (-> f :ret :spelling) " " (:name f) "("
               (str/join ", "
                         (eduction
                          (map (fn [arg]
                                 (str (:type arg)
                                      " "
                                      (:spelling arg)))
                               (:args f))))
               ")"
               "\n"
               doc))
           ~args
           (let [~cfn-sym (.getFunction ~(with-meta lib## {:tag 'com.sun.jna.NativeLibrary})
                                        ~(name fn-name))
                 args# (map (fn [coerce# arg#]
                              (coerce# arg#))
                            coercers#
                            ~args)]
             #_(prn "invoking "
                  ~(name fn-name)
                  (mapv type args#)
                  args#)
             (.invoke ~cfn-sym
                      ret-type# (to-array (map (fn [coerce# arg#]
                                                 (coerce# arg#))
                                               coercers#
                                               ~args)))))))))


(defmacro def-fn [lib f]
  (def-fn* lib f))

(defn def-enum* [enum]
  `(def ~(-> enum
             :name
             symbol)
     ~@(when-let [doc (:raw-comment enum)]
         (when (not= doc "")
           [doc]))
     ~(:value enum)))

(defmacro def-enum [enum]
  (def-enum* enum))


(defmacro def-api [lib api]
  `(let [api# ~api
         lib# ~lib]
     (run! #(eval (def-enum* %))
           (:enums api#))
     (run! def-struct (:structs api#))
     (run! #((eval (def-fn* %)) lib#) (:functions api#))))


(comment
  (require '[no.disassemble.r :as r]
           '[clojure.java.io :as io])
  (import '(org.eclipse.jdt.internal.core.util
            ClassFileReader)
          '(org.eclipse.jdt.core.util IClassFileReader))
  (defn reader->map [r]
    {:attributes      (->> r .getAttributes (map r/coerce))
     :major-version   (.getMajorVersion r)
     :minor-version   (.getMinorVersion r)
     :class?          (.isClass r)
     :interface?      (.isInterface r)
     :name            (symbol (String. (.getClassName r)))
     :superclass-name (symbol (String. (.getSuperclassName r)))
     :interface-names (.getInterfaceNames r)
     :fields          (->> r .getFieldInfos (map r/coerce))
     :methods         (->> r .getMethodInfos (map r/coerce))})

  (defn file->bytes [fname]
    (let [bos (java.io.ByteArrayOutputStream.)]
      (with-open [fis (java.io.FileInputStream. (io/file fname))]
        (io/copy fis bos))
      (.toByteArray bos)))

  (def class-info
    (reader->map
     (ClassFileReader. (file->bytes "target/classes/com/phronemophobic/clong/MyCallback.class") IClassFileReader/ALL)))

  (clojure.pprint/pprint class-info)

  (defn print-class [class-info]
    (clojure.pprint/pprint
     (reader->map
      (ClassFileReader. (insn/get-bytes class-info) IClassFileReader/ALL)))
    )

  )

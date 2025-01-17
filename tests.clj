(ns tests
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [check process tokenize]]
   [babashka.tasks :as tasks]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(defn test-file [name]
  (doto (fs/file (fs/temp-dir) "neil" name)
    (-> fs/parent (fs/create-dirs))
    (fs/delete-on-exit)))

(defn neil [arg & args]
  (let [tmp-file (test-file "deps.edn")]
    (apply tasks/shell "./neil"
           (concat (tokenize arg) [:deps-file tmp-file] args))
    (let [s (slurp tmp-file)]
      {:raw s
       :edn (edn/read-string s)})))

(deftest add-dep-test
  (let [{:keys [edn]} (neil "add dep clj-kondo/clj-kondo")]
    (is (-> edn :deps (get 'clj-kondo/clj-kondo)))))

(defn run-dep-subcommand [subcommand & args]
  (-> (process (concat ["./neil" "dep" subcommand] args) {:out :string})
      check :out str/split-lines))

(defn run-dep-versions [lib & args]
  (apply run-dep-subcommand "versions" lib args))

(deftest dep-versions-test
  (is (seq (run-dep-versions 'org.clojure/clojure))
      "We're able to find at least one Clojure version")
  (is (= 3
         (count (run-dep-versions 'hiccup/hiccup :limit 3)))
      "We're able to find exactly 3 hiccup versions"))

(deftest dep-search-test
  (is (thrown? java.lang.Exception (run-dep-subcommand "search" "someBougusLibThatDoesntExist")))
  (is (not-empty (run-dep-subcommand "search" "hiccups")))
  (is (some #(str/starts-with? % ":lib hiccups/hiccups" )
            (run-dep-subcommand "search" "hiccups")))
  (is (some #(re-matches  #":lib hiccups/hiccups :version \d+(\.\d+)+" % )
            (run-dep-subcommand "search" "hiccups")))
  (is (some #(re-matches  #":lib macchiato/hiccups :version \d+(\.\d+)+" % )
            (run-dep-subcommand "search" "hiccups")))
  ; tests for no NPEs/json parsing exceptions
  (is (any? (run-dep-subcommand "search" "org.clojure/tools.cli")))
  (is (any? (run-dep-subcommand "search" "babashka nrepl")))
  (is (thrown-with-msg? Exception #"Unable to find"
        (run-dep-subcommand "search" "%22searchTermThatIsn'tFound"))))

(defn run-license [filename subcommand & [args]]
  (let [args (or args "")]
    (-> (process (concat ["./neil" "license" subcommand]
                   (tokenize args) (when filename [:file filename])) {:out :string})
      check :out str/split-lines)))

(deftest license-list-test
  (testing "list/search with no args returns lines with key and name"
    (is (every? #(re-find #"^:license.*:name" %) (run-license nil "list"))))
  (testing "search with matching term prints results"
    (is (not-empty (run-license nil "search" "license"))))
  (testing "search for non-existing license prints error"
    (is (thrown-with-msg? Exception #"No licenses" (run-license nil "search" "nonExistentLicense")))))

(deftest license-add-test
  (let [out-file (test-file "LICENSE.txt")]
    (testing "license add creates license file (:license key elided)"
      (run-license out-file "add" "epl-2.0")
      (is (str/includes? (slurp out-file) "Eclipse Public License")))
    (testing "license add creates license file (with :license key)"
      (run-license out-file "add" ":license epl-2.0")
      (is (str/includes? (slurp out-file) "Eclipse Public License")))
    (testing "missing license key errors"
      (is (thrown-with-msg? Exception #"No license key" 
            (run-license out-file "add"))))
    (testing "invalid license key errors"
      (is (thrown-with-msg? Exception #"nonExistentLicense" 
            (run-license out-file "add" "nonExistentLicense"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (t/run-tests *ns*))

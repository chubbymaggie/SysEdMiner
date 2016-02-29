(ns 
  ^{:doc "Sandbox to figure out how the change pattern miner works.."
  :author "Tim Molderez"}
  arvid.thesis.plugin.clj.test.main
  (:require [arvid.thesis.plugin.clj.git.repository :as repo])
  (:require [arvid.thesis.plugin.clj.strategies.strategyFactory :as stratfac])
  (:require [arvid.thesis.plugin.clj.main :as main]))

(def tpvision-repos
  ; Retrieve a list of all paths to TP Vision's git repositories (on the local filesystem)
  (memoize 
    (fn []
      (let [root-path "/Volumes/Disk Image/tpv/tpv-extracted/tpvision/"
            rdr (clojure.java.io/reader (str root-path "repos.txt"))]
        (for [line (line-seq rdr)]
          (str root-path line))))))

(defn append
  "Appends a line of text to a file"
  [filepath text]
  (spit filepath (str text "\n") :append true :create true))

(defn mine-commit
  "Look for frequent change patterns in a single commit
   @param commit        JGit Commit instance
   @param strategy      Determines how to group changes, and the equality relation between two changes
   @param verbosity     Console output verbosity level [0-3]
   @param results-path  Path to results file (created if it doesn't exist)
   @reutrn              Pair containing all distilled changes + all frequent patterns"
  [commit strategy min-support verbosity results-path]
  (let [file-filter (fn [filename] true)
        changes (main/get-changes-in-commit commit file-filter verbosity)
        patterns (main/mine-changes changes strategy min-support verbosity)]
    
    ; Update results file if any patterns are found
    (if (not (empty? (:patterns-list  patterns)))
      (do 
        (append results-path (str "CommitID:" (.toString (:jgit-commit commit))))
        (append results-path (str "CommitMsg:" (:message commit)))
        (append results-path " ")
        ; For each pattern
        (doseq [pattern (:patterns-list patterns)]
          (append results-path (str "Support:" (:support pattern)))
          ; Write change types (insert, delete, ..)
          (let [inst-changes (:changes (:group (:gengroup (first (:instances pattern)))))
                change-types (clojure.string/join " " (for [change inst-changes] (name (:operation change))))]
            (append results-path (str "ChangeTypes:" change-types))
            (append results-path " "))
          ; For each instance of the pattern
          (doseq [instance (:instances pattern)]
            (let [gengroup (:gengroup instance)
                  group (:group gengroup)
                  container (:container gengroup)]
              (append results-path (str "ContainerMethod:"
                                        (.getName (.getPackage (.getRoot container))) "."
                                        (.getName (first (.types (.getRoot container)))) "."
                                        (.getName container)))
              (append results-path (str "ChangeIDs:" 
                                        (clojure.string/join 
                                          " "
                                          (for [change (:changes group)]
                                            (.indexOf changes change)))))
              (append results-path (str "ChangeNodeTypes:" 
                                        (clojure.string/join 
                                          " "
                                          (for [change (:changes group)]
                                            (.getName (.getClass (:copy change)))))))
              (append results-path "------")
              ))
          (append results-path "======")
          )))
    [changes
     (main/mine-changes changes strategy min-support verbosity)]))

(defn analyse-commits
  "Look for change patterns in multiple commits
   @param repo-path     File path to the .git directory of a repository
   @param results-path  Path to results file (created if it doesn't exist)
   @param strategy      Preprocessing strategy (see strategyFactory.clj)
   @param start-idx     Start the analysis at the given index, and continue until the last commit"
  [repo-path results-path strategy start-idx]
  (let [all-commits (repo/get-commits repo-path)
        commit-no (count all-commits)
        commits (take-last (- commit-no start-idx) all-commits)
;        commits (if (= limit 0)
;                  all-commits
;                  (take limit all-commits))
        strategy (stratfac/make-strategy)
        min-support 3
        verbosity 1]
    (map-indexed 
      (fn [idx commit]
        (println "Processing commit" (+ start-idx idx) "/" commit-no)
        (mine-commit commit strategy min-support verbosity results-path))
      commits)
;    (for [commit commits]
;      (mine-commit commit strategy min-support verbosity results-path))
    ))

(defn build-support-map [init-support-map results-path]
  (with-open [rdr (clojure.java.io/reader results-path)]
    (let [lines (line-seq rdr)]
      (loop [line (first lines) 
             rest-lines (rest lines) 
             support-map init-support-map]
        (if (empty? rest-lines)
          support-map
          (if (.startsWith line "Support")
            ; Update the support map
            (let [supp (java.lang.Integer/parseInt (second (clojure.string/split line #":")))
                  length (count (clojure.string/split (first rest-lines) #" ")) ; Count number of entries in the ChangeTypes: line
                  cur-val (if (nil? (get support-map supp))
                            {:count 0 :avg-length 0 :max-length 0}
                            (get support-map supp))
                  cnt (:count cur-val)
                  new-val {:count (inc cnt)
                           :avg-length (if (= cnt 0)
                                         length
                                         (+
                                           (* (/ (dec cnt) cnt) (:avg-length cur-val))
                                           (* (/ 1 cnt) length)))
                           :max-length (if (> length (:max-length cur-val))
                                         length
                                         (:max-length cur-val))}
                  ]
              (recur (second rest-lines) (rest (rest rest-lines))
                     (assoc support-map supp new-val)))
            
            ; Otherwise, just skip this line
            (recur (first rest-lines) (rest rest-lines) support-map))
          )))))

;(defn spit-change [filepath change]
;  (binding [*print-dup* true]
;    (spit filepath (pr-str change))))
;
;(defn
;  slurp-change
;  [filepath]
;  (binding [*read-eval* true]
;    (read-string (slurp filepath))))

(comment
  
  ; Pretty-print the entire support map
  (def supp-map
    (-> (build-support-map {} "/Users/soft/desktop/tpv-freqchanges/freqchanges.txt")
     (build-support-map "/Users/soft/desktop/freqchanges-contd.txt")
     (build-support-map "/Users/soft/desktop/freqchanges-contd2.txt")
     (build-support-map "/Users/soft/desktop/freqchanges-contd3.txt")
     (build-support-map "/Users/soft/desktop/freqchanges-contd4.txt")
     (build-support-map "/Users/soft/desktop/freqchanges-contd5.txt")
     (build-support-map "/Users/soft/desktop/freqchanges-contd6.txt")
     ))
  
  (doseq [support (sort (keys supp-map))]
    (let [val (get supp-map support)]
      (println support "," (:count val) "," (double (:avg-length val)) "," (:max-length val))))
  
  (def git-path (nth (tpvision-repos) 1))
  
  (count (repo/get-commits git-path))
  
  (inspector-jay.core/inspect (repo/get-commits git-path))
  (.indexOf (repo/get-commits git-path)
    (first (repo/get-commits git-path (fn [msg] (.startsWith msg "IPEPG Removed for TVJAR movement")))))
  
  (def changes (main/get-changes-in-commit (nth (repo/get-commits git-path) 7) (fn [f] true) 2))
  
  (analyse-commits 
    git-path
    "/Users/soft/desktop/freqchanges-contd6.txt"
    (stratfac/make-strategy)
    1410)
  
  (inspector-jay.core/inspect (repo/get-commits "/Users/soft/Documents/Github/Return_from_the_Void/.git"))
  (let [repo "/Users/soft/Documents/Github/Return_from_the_Void/.git"
        commit-message "Add sendMessage to service, keep track of open chats."
        strategy (stratfac/make-strategy)
        min-support 3
        verbosity 0]))
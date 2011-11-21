(ns circle.backend.git
  "fns for interacting with git."
  (:import java.io.File)
  (:use [clojure.tools.logging :only (infof)])
  (:use [arohner.utils :only (inspect)])
  (:require [circle.sh :as sh]))

(def repo-root "repos/")  ;; Root directory where we will check out repos

(defn project-name
  "Infer the project name from the URL"
  [url]
  (last (clojure.string/split url #"/")))

(defn repo-exists? [path]
  (->
   (sh/shq [(stat ~path)])
   (:exit)
   (= 0)))

(defn default-repo-path [url]
  (str repo-root (project-name url)))

(defmacro with-temp-ssh-key-file
  "Writes the ssh-key to a temp file, executes body. f is the name of the variable that will hold the File for the ssh-key. Deletes the temp key file when done"
  [[f key] & body]
  `(let [key# ~key
         ~f (when key#
              (File/createTempFile "key" "rsa"))]
     (when key#
       (spit ~f key#))
     (try
       ~@body
       (finally
        (when ~f
          (.delete ~f))))))

(def git-ssh-path "./git_ssh.sh")

(defn git-fn*
  "Takes a seq of stevedore code. Executes it locally, with GIT_SSH and GIT_SSH_KEY set up."
  [steve & {:keys [ssh-key repo]}]
  (with-temp-ssh-key-file [f ssh-key]
    (sh/sh steve
           :environment (when ssh-key
                          {"GIT_SSH" git-ssh-path
                           "GIT_SSH_KEY" f})
           :pwd repo)))

(defn clone
  "Clone a git repo at url, writing the directory to path"
  [url & {:keys [path ssh-key]}]
  (let [path (or path (default-repo-path url))]
    (infof "git clone %s %s" url path)
    (git-fn* (sh/quasiquote
              (git clone ~url ~path))
             :ssh-key
             ssh-key)))

(defn pull
  "Git pull an existing repo"
  [repo & {:keys [ssh-key]}]
  (infof "git pull %s" repo)
  (git-fn* (sh/quasiquote
            (git pull))
           :ssh-key ssh-key
           :repo repo))

(defn ensure-repo
  "Ensures a repo is present, or clones it if not. Also updates the
  repo to the newest code unless :update is false"
  [url & {:keys [path ssh-key update]
          :or {update true}}]
  (let [path (or path (default-repo-path url))]
    (if (repo-exists? path)
      (when update
        (pull path))
      (clone url :path path :ssh-key ssh-key))))

(defn latest-local-commit
  "Returns the most recent commit id, on the current branch."
  [repo]
  (->
   (git-fn* (sh/quasiquote (git log -1 "--pretty=format:%H")))
   :out))

(defn latest-remote-commit
  "Returns the most recent on origin/master. Does not fetch."
  [repo]
  (->
   (git-fn* (sh/quasiquote (git log -1 "--pretty=format:%H" "origin/master")))
   :out))
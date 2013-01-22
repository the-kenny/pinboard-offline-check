(ns pinboard-offline-checker.core
  (:require [clojure.data.json :as json]
            [clj-http.client :as client]
            [clojure.string :as str]))

(defn all-posts [user token]
  (json/read-str
   (:body
    (client/get
     (format "https://api.pinboard.in/v1/posts/all?auth_token=%s:%s&format=json" user token)))))

(def all-posts* (memoize all-posts))

(defn is-offline? [url]
  (try
    (println "Collecting offline status for " url)
    (let [response (client/get url {:conn-timeout   (* 15 1000)
                                    :socket-timeout (* 15 1000)})]
      (not= (:status response) 200))
    (catch Exception e
      true)))

(defn already-offline-tagged? [post]
  (contains? (set (str/split (get post "tags" "") #" "))
             "is:offline"))

(defn- build-add-entry-from-post [post]
  {:url         (get post "href")
   :description (get post "description")
   :extended    (get post "extended")
   :tags        (get post "tags")
   :dt          (get post "time")
   :shared      (get post "shared")
   :toread      (get post "toread")})

(defn remove-offline-tag! [post user token]
  (when (already-offline-tagged? post)
    (client/post "https://api.pinboard.in/v1/posts/add"
                 {:query-params
                  (merge {:auth_token  (str user ":" token)}
                         (build-add-entry-from-post post)
                         {:replace true
                          :tags (str/replace (get post "tags") "is:offline" "")})})))

(defn add-offline-tag! [post user token]
  (when-not (already-offline-tagged? post)
    (client/post "https://api.pinboard.in/v1/posts/add"
                 {:query-params
                  (merge {:auth_token (str user ":" token)}
                         (build-add-entry-from-post post)
                         {:replace true
                          :tags (str (get post "tags") " is:offline")})})))

(defn update-offline-status! [user token]
  (println "Requesting all posts...")
  (let [all (all-posts user token)]
    (println "Got" (count all) "posts")
    (println "Collecting offline status... this might take a while")
    (doseq [post all]
      (let [x (with-meta post {:offline? (is-offline? (get post "href"))})]
        (when (-> x meta :offline?)
          (println "Adding tag 'is:offline' to '" (get x "description") "'")
          (add-offline-tag! x user token))
      
        (when (and (already-offline-tagged? x)
                   (not (-> x meta :offline?)))
          (println "Removing tag 'is:offline' to '" (get x "description") "'")
          (remove-offline-tag! x user token))))))

(defn -main [& [user token]]
  (update-offline-status! user token))

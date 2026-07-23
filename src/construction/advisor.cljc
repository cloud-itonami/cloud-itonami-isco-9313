(ns construction.advisor
  "Labour Advisor — the advisor named in this repository's README,
  proposing a construction-labour operation (dispatch a crew, approve
  unmarked-hazard-zone work, approve confined-space entry) from a site
  work order, safety plan and crew assignment. Swappable mock/llm; the
  advisor ONLY proposes — `construction.governor` checks the marked-
  zone membership and safety-plan sign-off independently and always
  escalates unmarked-hazard-zone-work and confined-space-entry
  decisions. Modeled on cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-crew-dispatch|:approve-unmarked-hazard-zone-work|:approve-confined-space-entry
               :effect :propose :site-id str :work-zone any :stake kw
               :confidence n :rationale str}. The marked-zones set and
  safety-plan sign-off state live on the registered site record itself
  (see `construction.store`), not on the proposal."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake site-id work-zone] :as request}]
  {:op op
   :effect :propose
   :site-id site-id
   :work-zone work-zone
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a construction-labour advisor. Given a request, propose an
   :op, the :site-id and :work-zone, an honest :confidence and a
   :stake. Never propose dispatch into a work zone that isn't in the
   site's registered marked-zones set, or a dispatch for a site whose
   safety plan isn't signed off — the governor checks both against the
   registered site record. Unmarked-hazard-zone work and confined-
   space entry always require human sign-off regardless of
   confidence.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))

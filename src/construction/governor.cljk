(ns construction.governor
  "ConstructionLabourGovernor — the independent safety/traceability
  layer named in this repository's README/business-model.md, gating
  every crew dispatch an advisor may propose for a site. The governor
  never dispatches hardware itself and never dispatches a crew into
  an unmarked hazard zone. Modeled on cloud-itonami-isco-4311's
  bookkeeping.governor. Task twist: a proposed work zone is a
  membership check against the site's registered marked-zones set,
  and a crew cannot be dispatched until the site's safety plan has
  been signed off.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance    — the general contractor/property
                              developer must be registered.
    2. no-actuation         — proposal :effect must be :propose (the
                              governor never dispatches hardware and
                              never dispatches a crew into an
                              unmarked hazard zone; it only gates what
                              the advisor may dispatch).
    3. site basis           — a crew-dispatch proposal must cite a
                              REGISTERED site belonging to this
                              client.
    4. marked-zone membership — the proposed work zone must be a
                              member of the site's registered
                              `:marked-zones` set (dispatching a crew
                              into an unmarked zone is an unsurveyed
                              hazard, not efficient scheduling).
    5. safety-plan signed off — the site must have
                              `:safety-plan-signed-off?` true before
                              any crew dispatch (dispatching without
                              sign-off is an unauthorized deployment,
                              not efficient staffing).
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off per
  business-model.md's Trust Controls — these are :high/
  :safety-critical regardless of confidence):
    6. :op :approve-unmarked-hazard-zone-work (no work in an unmarked
                              hazard zone without the governor gate).
    7. :op :approve-confined-space-entry (entering a confined space
                              always requires human sign-off).
    8. low confidence (< `confidence-floor`)."
  (:require [construction.store :as store]))

(def confidence-floor 0.6)

(def ^:private always-escalate-ops #{:approve-unmarked-hazard-zone-work
                                     :approve-confined-space-entry})

(defn- hard-violations [{:keys [request proposal]} client-record s]
  (let [{:keys [op work-zone]} proposal
        dispatch? (= :approve-crew-dispatch op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（governor は未測量区画へのクルー投入を直接実行しない）"})

      (and dispatch? (nil? s))
      (conj {:rule :unknown-site :detail "未登録 site へのクルー派遣提案は不可"})

      (and dispatch? s (not= (:client-id s) (:client-id request)))
      (conj {:rule :site-wrong-client :detail "site が別 client のもの"})

      (and dispatch? s (some? work-zone) (not (contains? (:marked-zones s) work-zone)))
      (conj {:rule :work-zone-not-marked
             :detail (str "作業区画 " work-zone " は登録済み測量済み区画集合の要素ではない（未測量区画への投入は未管理ハザードであって効率的スケジューリングではない）")})

      (and dispatch? s (not (:safety-plan-signed-off? s)))
      (conj {:rule :safety-plan-not-signed-off
             :detail "安全計画未承認の site へのクルー派遣は無許可投入であって効率的な人員配置ではない"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `construction.store/Store`. Pure — never
  mutates the store, never dispatches a crew into an unmarked hazard
  zone."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        s (some->> (:site-id proposal) (store/site store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record s)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-risky? (contains? always-escalate-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))

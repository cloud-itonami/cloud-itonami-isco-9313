(ns construction.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [construction.store :as store]
            [construction.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Construction"})
    (store/register-site! st {:site-id "S-1" :client-id "client-1"
                              :name "site-042"
                              :marked-zones #{"zone-a" "zone-b"}
                              :safety-plan-signed-off? true})
    st))

(defn- dispatch-op [zone]
  {:op :approve-crew-dispatch :effect :propose :site-id "S-1"
   :work-zone zone :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-marked-zone-and-signed-off
  (let [st (fresh-store)
        v (governor/check req {} (dispatch-op "zone-a") st)]
    (is (:ok? v))))

(deftest ok-for-either-marked-zone
  (testing "any marked zone in the set is acceptable"
    (let [st (fresh-store)]
      (is (:ok? (governor/check req {} (dispatch-op "zone-a") st)))
      (is (:ok? (governor/check req {} (dispatch-op "zone-b") st))))))

(deftest hard-on-work-zone-not-marked
  (testing "dispatching a crew into an unmarked zone is an unsurveyed hazard, not efficient scheduling"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (dispatch-op "zone-unmapped") :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :work-zone-not-marked (:rule %)) (:violations v))))))

(deftest hard-on-safety-plan-not-signed-off
  (testing "dispatching a crew without safety-plan sign-off is an unauthorized deployment, not efficient staffing"
    (let [st (store/mem-store)]
      (store/register-client! st {:client-id "client-1" :name "Kobo Construction"})
      (store/register-site! st {:site-id "S-1" :client-id "client-1"
                                :name "site-042"
                                :marked-zones #{"zone-a" "zone-b"}
                                :safety-plan-signed-off? false})
      (let [v (governor/check req {} (assoc (dispatch-op "zone-a") :confidence 0.99) st)]
        (is (:hard? v))
        (is (some #(= :safety-plan-not-signed-off (:rule %)) (:violations v)))))))

(deftest hard-on-unknown-site
  (let [st (fresh-store)
        v (governor/check req {} (assoc (dispatch-op "zone-a") :site-id "S-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-site (:rule %)) (:violations v)))))

(deftest hard-on-foreign-site
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (dispatch-op "zone-a") st)]
      (is (:hard? v))
      (is (some #(= :site-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (dispatch-op "zone-a") st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (dispatch-op "zone-a") :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest always-escalates-unmarked-hazard-zone-work-even-at-high-confidence
  (testing "no work in an unmarked hazard zone without the governor gate"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-unmarked-hazard-zone-work :effect :propose
                                    :site-id "S-1" :confidence 0.99 :stake :low} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest always-escalates-confined-space-entry-even-at-high-confidence
  (testing "entering a confined space always requires human sign-off"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-confined-space-entry :effect :propose
                                    :site-id "S-1" :confidence 0.99 :stake :low} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (dispatch-op "zone-a") :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

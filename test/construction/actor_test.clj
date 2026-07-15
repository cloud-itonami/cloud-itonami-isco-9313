(ns construction.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [construction.actor :as actor]
            [construction.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Construction"})
    (store/register-site! st {:site-id "S-1" :client-id "client-1"
                              :name "site-042"
                              :marked-zones #{"zone-a" "zone-b"}
                              :safety-plan-signed-off? true})
    st))

(deftest commits-a-within-marked-zone-signed-off-dispatch
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-crew-dispatch :stake :low
                 :site-id "S-1" :work-zone "zone-a"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-unmarked-zone-dispatch
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-crew-dispatch :stake :low
                 :site-id "S-1" :work-zone "zone-unmapped"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-approves-unmarked-hazard-zone-work-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-unmarked-hazard-zone-work :stake :low
                 :site-id "S-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))

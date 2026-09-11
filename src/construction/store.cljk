(ns construction.store
  "SSoT for the ISCO-08 9313 independent construction labour practice
  actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors
  section; README's 'Robotics premise' — a material-handling and
  site-prep robot performs debris clearing, material staging and
  site-marking under this advisor/governor pair, which never
  dispatches hardware itself and never dispatches a crew into an
  unmarked hazard zone). Modeled on cloud-itonami-isco-4311's
  bookkeeping.store.

  Domain:

    client — a registered general contractor/property developer
             (:client-id, :name)
    site   — a registered construction site {:site-id :client-id
             :name :marked-zones #{...} :safety-plan-signed-off?
             boolean}. `:marked-zones` is the registered set of
             surveyed/marked work zones a proposed crew dispatch's
             work zone must be a member of — dispatching a crew into
             an unmarked zone is an unsurveyed hazard, not efficient
             scheduling. `:safety-plan-signed-off?` records whether
             the site's safety plan has been signed off — dispatching
             a crew without safety-plan sign-off is an unauthorized
             deployment, not efficient staffing.
    record — a committed operating record (a dispatched crew task) —
             written ONLY via commit-record!.
    ledger — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (site [s site-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-site! [s site-rec])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (site [_ site-id] (get-in @a [:sites site-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-site! [s site-rec]
    (swap! a assoc-in [:sites (:site-id site-rec)] site-rec) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :sites {} :records [] :ledger []}
                                   seed)))))

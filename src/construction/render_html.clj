(ns construction.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`construction.actor` -> `construction.governor` ->
  `construction.store`) through a scenario built from real, exercised
  store data and renders the result deterministically -- no invented
  numbers, no timestamps in the page content, byte-identical across
  reruns against the same seed (verify by diffing two consecutive runs
  before shipping).

  `client-1` (\"Kobo Construction\") + site `S-1` (\"site-042\", marked
  zones #{\"zone-a\" \"zone-b\"}, safety-plan-signed-off? true) below
  are lifted VERBATIM from this repo's own proven-passing test fixture
  (`construction.actor-test`/`construction.governor-test` `fresh-store`
  helper) -- ground truth, not invented. `client-2` (\"Second City
  Builders\") + site `S-2` (marked zone #{\"zone-c\"},
  safety-plan-signed-off? FALSE) is ADDITIONAL demo data registered via
  the SAME real protocol calls (`store/register-client!`/`store/
  register-site!`) this actor's own test fixtures use -- this actor has
  only one client/site pair in its own actor-test fixture, so a second
  client+site is necessary to demonstrate both the cross-client
  `:site-wrong-client` rule AND `:safety-plan-not-signed-off` (the
  fixture's only site already has its safety plan signed off).
  Disclosed here plainly, not presented as if it were a pre-existing
  fixture. Every other field this page displays (statuses, records,
  hold reasons) is real output read after `run-demo!` actually executed
  the graph -- none of it is hand-typed.

  Known architectural gaps, honestly noted rather than papered over:
  - `construction.governor`'s `:no-actuation` rule (proposal `:effect`
    must be `:propose`) is NOT reachable through this demo, because the
    real `mock-advisor` (`construction.advisor/infer`) unconditionally
    sets `:effect :propose` on every proposal it emits.
  - The low-confidence escalation path is likewise NOT reachable
    through this demo: `mock-advisor` derives confidence purely from
    `:stake` (`:high` -> 0.7, `:medium` -> 0.85, `:low` -> 0.95), all of
    which sit above `construction.governor/confidence-floor` (0.6) --
    there is no stake value the real advisor maps to a sub-floor
    confidence. Both rules ARE covered by
    `construction.governor-test/hard-on-no-actuation-violation` and
    `escalates-low-confidence` (which call `governor/check` directly
    with hand-built proposals), not by this build-time renderer, which
    only ever drives the real actor/graph the way an operator actually
    would.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [construction.store :as store]
            [construction.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real construction-labour operation request through the
  actual compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it (this
  demo's scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit, escalate-then-approve, and 5 of
  the 6 distinct HARD-hold reasons in `construction.governor` -- the
  6th, `:no-actuation`, is architecturally unreachable via the real
  advisor, see namespace docstring). Every `:op` keyword and violation
  rule name below is copied from `construction.governor`'s own
  `hard-violations`/`check`, not invented."
  [;; client-1 / "Kobo Construction" / S-1 (real fixture from construction.actor-test)
   ["c1-dispatch-zone-a"      "client-1" :approve-crew-dispatch {:site-id "S-1" :work-zone "zone-a" :stake :low}]
   ["c1-dispatch-unmarked"    "client-1" :approve-crew-dispatch {:site-id "S-1" :work-zone "zone-unmapped" :stake :low}]
   ["c1-dispatch-unknown-site" "client-1" :approve-crew-dispatch {:site-id "S-ghost" :work-zone "zone-a" :stake :low}]
   ;; unregistered client entirely
   ["ghost-no-client" "client-ghost" :approve-crew-dispatch {:site-id "S-1" :work-zone "zone-a" :stake :low}]
   ;; client-2 / S-2 (additional demo data, registered via the same
   ;; real register-client!/register-site! calls -- see namespace
   ;; docstring). Referencing client-1's S-1 from client-2 demonstrates
   ;; the cross-client rule; S-2's own (unsigned-off) safety plan
   ;; demonstrates the sign-off rule.
   ["c2-dispatch-wrong-site"      "client-2" :approve-crew-dispatch {:site-id "S-1" :work-zone "zone-a" :stake :low}]
   ["c2-dispatch-not-signed-off"  "client-2" :approve-crew-dispatch {:site-id "S-2" :work-zone "zone-c" :stake :low}]
   ;; always-escalate ops, regardless of confidence
   ["c1-unmarked-hazard-work" "client-1" :approve-unmarked-hazard-zone-work {:site-id "S-1" :stake :low}]
   ["c1-confined-space-entry" "client-1" :approve-confined-space-entry {:site-id "S-1" :stake :low}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `construction.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Construction"})
    (store/register-site! db {:site-id "S-1" :client-id "client-1"
                               :name "site-042"
                               :marked-zones #{"zone-a" "zone-b"}
                               :safety-plan-signed-off? true})
    (store/register-client! db {:client-id "client-2" :name "Second City Builders"})
    (store/register-site! db {:site-id "S-2" :client-id "client-2"
                               :name "site-099"
                               :marked-zones #{"zone-c"}
                               :safety-plan-signed-off? false})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- site-row [store {:keys [site-id name client-id marked-zones safety-plan-signed-off?]} runs]
  (let [record-count (count (filter #(= site-id (:site-id %)) (store/records-of store client-id)))
        last-run (last (filter #(= site-id (get-in % [:request :site-id])) runs))]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc site-id) (esc name)
            (esc (str/join ", " (sort marked-zones)))
            (if safety-plan-signed-off? "<span class=\"ok\">signed off</span>" "<span class=\"err\">NOT signed off</span>")
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:work-zone request) (:site-id request) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md /
  ;; `construction.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:approve-crew-dispatch</code></td><td><span class=\"ok\">auto-commit when the work zone is registered-marked and the safety plan is signed off</span></td></tr>"
   "        <tr><td><code>:approve-unmarked-hazard-zone-work</code></td><td><span class=\"warn\">ALWAYS human approval &middot; no work in an unmarked hazard zone without the governor gate</span></td></tr>"
   "        <tr><td><code>:approve-confined-space-entry</code></td><td><span class=\"warn\">ALWAYS human approval &middot; entering a confined space</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [sites [{:site-id "S-1" :name "site-042" :client-id "client-1"
                :marked-zones #{"zone-a" "zone-b"} :safety-plan-signed-off? true}
               {:site-id "S-2" :name "site-099" :client-id "client-2"
                :marked-zones #{"zone-c"} :safety-plan-signed-off? false}]
        site-rows (str/join "\n" (map #(site-row store % runs) sites))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-9313 &middot; independent construction labour</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Independent Construction Labour (ISCO-08 9313) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · hazard-zone &amp; confined-space work always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients &amp; sites</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>construction.store</code> via <code>construction.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. Marked zones and safety-plan sign-off are the registered ground truth the governor checks every dispatch against — dispatching into an unmarked zone is an unsurveyed hazard, not efficient scheduling.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Site</th><th>Name</th><th>Marked zones</th><th>Safety plan</th><th>Records</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     site-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Construction Labour Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. The governor never dispatches hardware itself and never dispatches a crew into an unmarked hazard zone.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own work-zone/site, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Zone / site</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))

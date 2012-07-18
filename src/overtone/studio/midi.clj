(ns overtone.studio.midi
  (:use [overtone.sc node]
        [overtone midi]
        [overtone.at-at :only (mk-pool every)]
        [overtone.libs.event])
  (:require [overtone.config.log :as log]))

(defonce MIDI-POOL (mk-pool))
(defonce midi-devices* (atom {}))
(defonce midi-control-agents* (atom {}))

(def EXCLUDED-DEVICES #{"Real Time Sequencer" "Java Sound Synthesizer"})

(defn midi-mk-full-device-event-key
  "Creates the device-specific part of the key used for events generated
  by incoming MIDI messages."
  [dev command]
  [:midi-device (dev :vendor) (dev :name) (dev :description) command])

(defn midi-mk-full-control-event-key
  "Creates the full key used for events generated by incoming MIDI
  messages. Using this key can allow you to detect events from specific
  devices rather than just general control events."
  [dev command control-id]
  (concat (midi-mk-full-device-event-key dev command) [control-id]))

(defn midi-event
  "Trigger a global midi event."
  [dev msg & [ts]]
  (let [command (:command msg)]
    (event [:midi command] msg)
    (event (midi-mk-full-control-event-key dev command (:data1 msg)) msg)))

(defn- detect-midi-devices
  "Designed to run periodically and update the midi-devices* atom with
  the latest list of midi sources and add event handlers to new devices."
  []
  (try
    (let [old-devs     (set (keys @midi-devices*))
          devs         (midi-sources)
          devs         (filter #(not (old-devs (:device %))) devs)
          receivers    (doall (filter
                               (fn [dev]
                                 (try
                                   (midi-handle-events (midi-in dev) #(midi-event dev %1))
                                   true
                                   (catch Exception e
                                     (log/warn "Can't listen to midi device: " dev "\n" e)
                                     false)))
                               devs))
          device-names (map :name devs)
          n-devs       (count device-names)
          dev-map      (apply hash-map (interleave (map :device devs) receivers))]
      (swap! midi-devices* merge dev-map)
      (when (pos? n-devs)
         (log/info "Connected " n-devs " midi devices: " device-names)))
    (catch Exception ex
      (println "Got exception in detect-midi-devices!" ex))))

; The rate at which we poll for new midi devices
(def MIDI-POLL-RATE 2000)

(defonce __DEVICE-POLLER__
  (detect-midi-devices))
;  (every MIDI-POLL-RATE #'detect-midi-devices MIDI-POOL :desc "Check for new midi devices"))

(defn midi-poly-player
  "Sets up the event handlers and manages synth instances to easily play
  a polyphonic instrument with a midi controller.  The play-fn should
  take the note and velocity as the only two arguments, and the synth
  should have a gate parameter that can be set to zero when a :note-off
  event is received.

    (definst ding
      [note 60 velocity 100 gate 1]
      (let [freq (midicps note)
            amp  (/ velocity 127.0)
            snd  (sin-osc freq)
            env  (env-gen (adsr 0.001 0.1 0.6 0.3) gate :action FREE)]
        (* amp env snd)))

    (def dinger (midi-poly-player ding))
  "
  [play-fn]
  (let [notes*  (atom {})
        on-key  (keyword (gensym 'note-on))
        off-key (keyword (gensym 'note-off))]

    (on-event [:midi :note-on] (fn [{note :note velocity :velocity}]
                                 (swap! notes* assoc note (play-fn note velocity)))
              on-key)

    (on-event [:midi :note-off] (fn [{note :note velocity :velocity}]
                                  (when-let [n (get @notes* note)]
                                    (node-control n [:gate 0])
                                    (swap! notes* dissoc note)))
              off-key)

    ;; TODO listen for '/n_end' event for nodes that free themselves
    ;; before recieving a note-off message.
    {:notes* notes*
     :on-key on-key
     :off-key off-key
     :status (atom :playing)}))

(defn- midi-control-handler
  [state-atom handler mapping msg]
  (let [[ctl-name scale-fn] (get mapping (:note msg))
        ctl-val  (scale-fn (:velocity msg))]
    (swap! state-atom assoc ctl-name ctl-val)
    (handler ctl-name ctl-val)))

(defn midi-inst-controller
  "Create a midi instrument controller for manipulating the parameters of an instrument
  using an external device.  Requires an atom to store the state of the parameters, a
  handler that will be called each time a parameter is modified, and a mapping table to
  specify how midi control messages should manipulate the parameters.

  (def ding-mapping
    {22 [:attack     #(* 0.3 (/ % 127.0))]
     23 [:decay      #(* 0.6 (/ % 127.0))]
     24 [:sustain    #(/ % 127.0)]
     25 [:release    #(/ % 127.0)]})

  (def ding-state (atom {}))

  (midi-inst-controller ding-state (partial ctl ding) ding-mapping)
  "
  [state-atom handler mapping]
  (let [ctl-key (keyword (gensym 'control-change))]
    (on-event [:midi :control-change]
              #(midi-control-handler state-atom handler mapping %)
              ctl-key)))

; TODO: remove-handler doesn't seem to work... ask Sam
(defn stop-midi-player
  [player]
  (remove-handler (:on-key player))
  (remove-handler (:off-key player))
  (reset! (:status player) :stopped)
  player)


(defn midi-capture-next-control-input
  "Returns a simple map representing next modified controller. Will
  block the current thread until a new MIDI control-change event is
  received.

  Useful for detecting controller information. If the argument with-key?
  is set to true, the full event key will be associated with the
  resulting map."
  ([] (midi-capture-next-control-input false))
  ([with-key?]
     (let [p (promise)]
       (oneshot-event [:midi :control-change]
                      (fn [msg]
                        (let [{controller :data1 val :data2} msg
                              device-name                    (get-in msg [:device :name])
                              res {:controller controller :value val}
                              res (if with-key?
                                    (assoc res :key (midi-mk-full-device-event-key (:device msg) :control-change))
                                    res)]

                          (deliver p res)))
                      ::print-next-control-input)
       @p)))

(defn midi-capture-next-controller-key
  "Returns a vector representing the unique key for the next modified
  controller."
  []
  (:key (midi-capture-next-control-input true)))

(defn midi-capture-next-controller-control-key
  "Returns a vector representing the unique key for the specific control
  of the next modified controller. This is the key used for controller
  specific MIDI events."
  []
  (let [next-input (midi-capture-next-control-input true)]
    (concat (:key next-input) [(:controller next-input)])))

(defn- mk-agent-for-control-keyword
  [control-key]
  (keyword (str "overtone.studio.midi/update-agent-for-control-" control-key)))

(defn midi-agent-for-control
  "Returns an agent representing the current value of a
  controller-specific control identified by a fully-qualified MIDI event
  key such as that generated by midi-mk-full-control-event-key. If the
  atom doesn't exist, it is created and cached. Subsequent calls with
  the same control-key will return the same agent.

  Agents are used because the event to update them can be safely handled
  synchronously (with on-sync-event) without watchers being able to
  block the thread generating the MIDI events. This also means that
  incoming events are sent to the agent in the correct order whereas if
  the thread pool were used (via on-event), the incoming events may be
  arbitrarily ordered."
  [control-key]
  (let [control-agents (swap! midi-control-agents*
                             (fn [prev]
                               (if (get prev control-key)
                                 prev
                                 (let [new-control-agent (agent 0)]
                                   (on-sync-event control-key
                                             (fn [msg]
                                               (send new-control-agent
                                                     (fn [old-val]
                                                       (:data2 msg))))
                                             (mk-agent-for-control-keyword control-key))
                                   (assoc prev control-key new-control-atom)))))]
    (get control-agents control-key)))

package org.jestron.event.game;

import org.jestron.event.model.EventDef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Çalışan tek bir event oturumu. */
public final class EventSession {

    public enum Phase {JOIN, RUNNING, ENDED}

    public final EventDef def;
    public Phase phase = Phase.JOIN;
    public int joinLeft;
    public final int joinTotal;
    public int round;
    /** Bu turdaki dalga numarası */
    public int wave;
    public boolean roundTransition;
    public int eliminationCounter;
    public final Map<UUID, Participant> participants = new LinkedHashMap<>();
    public final Deque<UUID[]> queue = new ArrayDeque<>();
    /** oda anahtarı -> maç */
    public final Map<String, Match> matches = new HashMap<>();

    public EventSession(EventDef def, int joinSeconds) {
        this.def = def;
        this.joinLeft = joinSeconds;
        this.joinTotal = joinSeconds;
    }

    public Participant get(UUID uuid) {
        return uuid == null ? null : participants.get(uuid);
    }

    public List<Participant> alive() {
        List<Participant> out = new ArrayList<>();
        for (Participant p : participants.values()) if (p.active()) out.add(p);
        return out;
    }

    public int aliveCount() {
        int c = 0;
        for (Participant p : participants.values()) if (p.active()) c++;
        return c;
    }
}

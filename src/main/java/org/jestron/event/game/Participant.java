package org.jestron.event.game;

import java.util.UUID;

public final class Participant {

    public enum State {
        /** Bekleme alanında, sıradaki turu bekliyor */
        WAITING,
        /** Bu turda eşleşti, boş oda bekliyor */
        QUEUED,
        /** Odada maçta */
        IN_MATCH,
        /** Elendi / ayrıldı */
        ELIMINATED
    }

    public final UUID uuid;
    public final String name;
    public State state = State.WAITING;
    public int kills;
    public int matchesPlayed;
    public int byes;
    public int eliminatedRound;
    public int eliminationIndex = -1;
    public boolean champion;
    public Match match;

    public Participant(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public boolean active() {
        return state != State.ELIMINATED;
    }

    public boolean inLiveMatch() {
        return state == State.IN_MATCH && match != null && match.started && !match.ended;
    }
}

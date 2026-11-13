package org.jestron.event.game;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.jestron.event.model.Room;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Match {

    public record BlockKey(int x, int y, int z) {
    }

    public final Room room;
    public final UUID a;
    public final UUID b;
    public final String aName;
    public final String bName;
    public int countdown;
    public int timeLeft;
    public boolean started;
    public boolean ended;
    public UUID winner;
    /** Maç sırasında değişen blokların ORİJİNAL hali. Maç bitince geri yüklenir. */
    public final Map<BlockKey, BlockData> changes = new HashMap<>();

    public Match(Room room, Participant pa, Participant pb, int countdown) {
        this.room = room;
        this.a = pa.uuid;
        this.b = pb.uuid;
        this.aName = pa.name;
        this.bName = pb.name;
        this.countdown = countdown;
    }

    public UUID opponent(UUID u) {
        return a.equals(u) ? b : a;
    }

    public String opponentName(UUID u) {
        return a.equals(u) ? bName : aName;
    }

    public void record(Block b) {
        changes.putIfAbsent(new BlockKey(b.getX(), b.getY(), b.getZ()), b.getBlockData());
    }

    public void record(BlockState s) {
        changes.putIfAbsent(new BlockKey(s.getX(), s.getY(), s.getZ()), s.getBlockData());
    }

    public boolean isChanged(Block b) {
        return changes.containsKey(new BlockKey(b.getX(), b.getY(), b.getZ()));
    }
}

package org.gpur.waypoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.Test;

class WaypointEntrySnapshotTest {
    @Test
    void rowSnapshotKeepsEntryOrderAndShallowEntryBehaviorDuringRemovalAndReplacement() {
        assertSnapshotEquivalence(false);
    }

    @Test
    void transposedColumnSnapshotKeepsEntryOrderAndShallowEntryBehaviorDuringRemovalAndReplacement() {
        assertSnapshotEquivalence(true);
    }

    private static void assertSnapshotEquivalence(boolean transposedColumn) {
        Table<String, String, String> listTable = table(transposedColumn);
        Table<String, String, String> setTable = table(transposedColumn);
        Map<String, String> listView = view(listTable, transposedColumn);
        Map<String, String> setView = view(setTable, transposedColumn);

        List<Entry<String, String>> sourceEntries = new ArrayList<>(listView.entrySet());
        List<Entry<String, String>> listSnapshot = List.copyOf(sourceEntries);
        List<Entry<String, String>> setSnapshot = new ArrayList<>(ImmutableSet.copyOf(setView.entrySet()));

        assertEquals(keys(sourceEntries), keys(listSnapshot));
        assertEquals(keys(new ArrayList<>(setView.entrySet())), keys(setSnapshot));
        assertEquals(keys(listSnapshot), keys(setSnapshot));
        for (int i = 0; i < sourceEntries.size(); i++) {
            // List.copyOf copies the container while preserving the live entry references.
            assertSame(sourceEntries.get(i), listSnapshot.get(i));
        }

        assertEquals(visitAndMutate(listSnapshot, listView), visitAndMutate(setSnapshot, setView));
        assertEquals("replacement", listView.get(listSnapshot.get(2).getKey()));
        assertFalse(listView.containsKey(listSnapshot.get(1).getKey()));
        assertEquals("replacement", setView.get(setSnapshot.get(2).getKey()));
        assertFalse(setView.containsKey(setSnapshot.get(1).getKey()));
    }

    private static List<String> visitAndMutate(List<Entry<String, String>> snapshot, Map<String, String> view) {
        List<String> visited = new ArrayList<>();
        String removedKey = snapshot.get(1).getKey();
        String replacedKey = snapshot.get(2).getKey();
        for (int i = 0; i < snapshot.size(); i++) {
            Entry<String, String> entry = snapshot.get(i);
            visited.add(entry.getKey() + "=" + entry.getValue());
            if (i == 0) {
                view.remove(removedKey);
                view.put(replacedKey, "replacement");
            }
        }
        return visited;
    }

    private static List<String> keys(List<? extends Entry<String, String>> entries) {
        return entries.stream().map(Entry::getKey).toList();
    }

    private static Table<String, String, String> table(boolean transposedColumn) {
        Table<String, String, String> table = HashBasedTable.create();
        if (transposedColumn) {
            table.put("player-a", "waypoint", "a");
            table.put("player-b", "waypoint", "b");
            table.put("player-c", "waypoint", "c");
        } else {
            table.put("player", "waypoint-a", "a");
            table.put("player", "waypoint-b", "b");
            table.put("player", "waypoint-c", "c");
        }
        return table;
    }

    private static Map<String, String> view(Table<String, String, String> table, boolean transposedColumn) {
        return transposedColumn ? Tables.transpose(table).row("waypoint") : table.row("player");
    }
}

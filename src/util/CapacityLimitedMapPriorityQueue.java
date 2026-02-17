package util;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class CapacityLimitedMapPriorityQueue<K, V extends Comparable<V>> {
    private final PriorityQueue<Entry<K, V>> queue;
    private final int capacity;
    private final Map<K, V> map;

    public CapacityLimitedMapPriorityQueue(int capacity) {
        this.capacity = capacity;
        // 创建基于Entry值的大顶堆
        this.queue = new PriorityQueue<>((a, b) -> b.getValue().compareTo(a.getValue()));
        this.map = new HashMap<>();
    }

    public void put(K key, V value) {
        // 移除重复key的旧值
        if (map.containsKey(key)) {
            queue.remove(new AbstractMap.SimpleEntry<>(key, map.get(key)));
            map.remove(key);
        }

        Entry<K, V> entry = new AbstractMap.SimpleEntry<>(key, value);
        queue.offer(entry);
        map.put(key, value);

        // 容量控制
        while (queue.size() > capacity) {
            Entry<K, V> removed = queue.poll();
            if (removed != null) {
                map.remove(removed.getKey());
            }
        }
    }

    public List<Entry<K, V>> getSortedEntries() {
        List<Entry<K, V>> list = new ArrayList<>(queue);
        list.sort(Entry.comparingByValue()); // 从小到大排序
        return list;
    }

    public List<K> getSortedKeys() {
        return getSortedEntries().stream()
                .map(Entry::getKey)
                .collect(Collectors.toList());
    }


    public static void main(String[] args) {
        CapacityLimitedMapPriorityQueue<String, Integer> queue =
                new CapacityLimitedMapPriorityQueue<>(3);

        System.out.println("Start:");
        queue.getSortedEntries().forEach(e ->
                System.out.print(e.getKey() + " -> " + e.getValue() + ", ")
        );
        System.out.println();

        queue.put("N1", 5);
        queue.getSortedEntries().forEach(e ->
                System.out.print(e.getKey() + " -> " + e.getValue() + ", ")
        );
        System.out.println();

        queue.put("N2", 3);
        queue.getSortedEntries().forEach(e ->
                System.out.print(e.getKey() + " -> " + e.getValue() + ", ")
        );
        System.out.println();

        queue.put("N3", 2);
        queue.getSortedEntries().forEach(e ->
                System.out.print(e.getKey() + " -> " + e.getValue() + ", ")
        );
        System.out.println();

        queue.put("N4", 1);
        queue.getSortedEntries().forEach(e ->
                System.out.print(e.getKey() + " -> " + e.getValue() + ", ")
        );
        System.out.println();

        queue.put("N5", 4);
        queue.getSortedEntries().forEach(e ->
                System.out.print(e.getKey() + " -> " + e.getValue() + ", ")
        );
        System.out.println();

        queue.put("N6", -2);

        queue.getSortedEntries().forEach(e ->
                System.out.print(e.getKey() + " -> " + e.getValue() + ", ")
        );
        System.out.println();

        System.out.println("Sorted Keys:");
        queue.getSortedKeys().forEach(k -> System.out.print(k + ", "));
        // 输出：N6, N4, N3,
    }
}

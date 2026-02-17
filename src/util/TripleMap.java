package util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class TripleMap<A, B, C> {
    // 内部键类型，用于主映射的复合键
    private static class Pair<K, V> {
        private final K key;
        private final V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Pair<?, ?> pair = (Pair<?, ?>) o;
            return Objects.equals(key, pair.key) &&
                    Objects.equals(value, pair.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }
    }

    // 主映射：直接存储(A,B)到C的映射
    private final Map<Pair<A, B>, C> mainMap = new HashMap<>();
    // 按A的索引：存储A到{B→C}的映射
    private final Map<A, Map<B, C>> aIndex = new HashMap<>();
    // 按B的索引：存储B到{A→C}的映射
    private final Map<B, Map<A, C>> bIndex = new HashMap<>();
    // 新增缓存复合键的结构
    private final Map<A, Map<B, Pair<A, B>>> pairsCache = new HashMap<>();

    /**
     * 插入/更新键值对，同步维护三个映射
     */
    public void put(A a, B b, C c) {
        // 修改创建复合键的方式，优先从缓存获取
        Map<B, Pair<A, B>> aCache = pairsCache.computeIfAbsent(a, k -> new HashMap<>());
        Pair<A, B> compositeKey = aCache.computeIfAbsent(b, b1 -> new Pair<>(a, b1));

        // 更新主映射
        mainMap.put(compositeKey, c);

        // 更新A索引
        aIndex.computeIfAbsent(a, k -> new HashMap<>())
                .put(b, c);

        // 更新B索引
        bIndex.computeIfAbsent(b, k -> new HashMap<>())
                .put(a, c);
    }

    /**
     * 根据A和B查询C
     */
    public C get(A a, B b) {
        // 替换直接new Pair的方式
        Map<B, Pair<A, B>> aCache = pairsCache.get(a);
        if (aCache == null) return null;
        Pair<A, B> compositeKey = aCache.get(b);
        if (compositeKey == null) return null;
        return mainMap.get(compositeKey);
    }

    /**
     * 根据A获取所有对应的B-C对
     */
    public Map<B, C> getByA(A a) {
        return aIndex.getOrDefault(a, Collections.emptyMap());
    }

    /**
     * 根据B获取所有对应的A-C对
     */
    public Map<A, C> getByB(B b) {
        return bIndex.getOrDefault(b, Collections.emptyMap());
    }

    /**
     * 删除指定键值对，同步维护三个映射
     */
    public void remove(A a, B b) {
        // 从缓存中获取compositeKey
        Map<B, Pair<A, B>> aCache = pairsCache.get(a);
        if (aCache == null) return;
        Pair<A, B> compositeKey = aCache.get(b);
        if (compositeKey == null) return;

        // 删除缓存中的记录
        aCache.remove(b);
        if (aCache.isEmpty()) pairsCache.remove(a);

        C removedC = mainMap.remove(compositeKey);

        if (removedC != null) {
            // 从A索引中移除
            Map<B, C> aMap = aIndex.get(a);
            if (aMap != null) {
                aMap.remove(b);
                if (aMap.isEmpty()) aIndex.remove(a);
            }

            // 从B索引中移除
            Map<A, C> bMap = bIndex.get(b);
            if (bMap != null) {
                bMap.remove(a);
                if (bMap.isEmpty()) bIndex.remove(b);
            }
        }
    }
}
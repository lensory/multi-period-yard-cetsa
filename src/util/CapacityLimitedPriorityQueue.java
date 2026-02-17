package util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

public class CapacityLimitedPriorityQueue<T extends Comparable<T>> {
    private final PriorityQueue<T> queue;
    private final int capacity;

    public CapacityLimitedPriorityQueue(int capacity) {
        this.capacity = capacity;
        // 使用反向比较器创建大顶堆
        this.queue = new PriorityQueue<>(Collections.reverseOrder());
    }

    public void add(T element) {
        queue.offer(element);
        // 超过容量时移除最大的元素（队首）
        if (queue.size() > capacity) {
            queue.poll();
        }
    }

    public List<T> getSortedElements() {
        List<T> list = new ArrayList<>(queue);
        Collections.sort(list); // 从小到大排序
        return list;
    }

    public int size() {
        return queue.size();
    }

    public static void main(String[] args) {
        CapacityLimitedPriorityQueue<Integer> queue = new CapacityLimitedPriorityQueue<>(3);

        System.out.println("Start: " + queue.getSortedElements());

        queue.add(5);
        System.out.println(queue.getSortedElements());
        queue.add(2);
        System.out.println(queue.getSortedElements());
        queue.add(3);
        System.out.println(queue.getSortedElements());
        queue.add(1);
        System.out.println(queue.getSortedElements());
        queue.add(4);
        System.out.println(queue.getSortedElements());
        queue.add(-2);
        System.out.println(queue.getSortedElements());

        System.out.println("Final: " + queue.getSortedElements());

    }
}

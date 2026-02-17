package util;

public class MyMathMethods {

    public static int greatestCommonDivisor(int a, int b) {
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    public static int leastCommonMultiple(int a, int b) {
        return a / greatestCommonDivisor(a, b) * b;
    }

    public static int leastCommonMultiple(int[] arr) {
        int result = arr[0];
        for (int i = 1; i < arr.length; i++) {
            result = leastCommonMultiple(result, arr[i]);
        }
        return result;
    }

    public static int ceilDiv(int s, int d) {
        return Math.floorDiv(s + d - 1, d);
    }
}

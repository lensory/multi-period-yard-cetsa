package util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;


public class CSVReader extends BufferedReader {
    private final String filename;
    private final String[] headers;
    private final boolean[] isSequence;
//    private final boolean endsWithSequence;

    private String curLine;
    private String[] curValues;

    // level-1 separator: ","
    // level-2 separator: ";"
    public CSVReader(String filename, String[] headers, boolean[] isSequence) throws IOException {
        super(new FileReader(filename));

        if (headers.length != isSequence.length)
            throw new IllegalArgumentException("The Lengths of 'headers' and 'isSequence' should be the same.");
        for (String header : headers)
            if (header == null || header.isEmpty())
                throw new IllegalArgumentException("Error: header cannot be empty.");

        this.filename = filename;
        this.curLine = null;
        this.headers = Arrays.copyOf(headers, headers.length);
        this.isSequence = Arrays.copyOf(isSequence, isSequence.length);

        this.readLine();
        if (!Arrays.equals(headers, curValues)) {
            throw new IllegalArgumentException(
                    String.format("Header mismatch in %s\nExpected: %s\nActual: %s",
                            filename, Arrays.toString(headers), Arrays.toString(curValues))
            );
        }
    }

    public CSVReader(String filename, String[] headers) throws IOException {
        this(filename, headers, new boolean[headers.length]);
    }

    public static Function<String, Integer> parseInt = Integer::parseInt;
    public static Function<String, Double> parseDouble = Double::parseDouble;
    public static Function<String, String> parseString = String::trim;
    public static Function<String, List<Integer>> parseIntArray =
            s -> {
                if (s == null || s.trim().isEmpty()) {
                    return List.of();
                }
                return Arrays.stream(s.split(";", -1))
                        .map(String::trim)
                        .map(Integer::parseInt)
                        .collect(Collectors.toList());
            };
    public static Function<String, List<Double>> parseDoubleArray =
            s -> {
                if (s == null || s.trim().isEmpty()) {
                    return List.of();
                }
                return Arrays.stream(s.split(";", -1))
                        .map(String::trim)
                        .map(Double::parseDouble)
                        .collect(Collectors.toList());
            };
    public static Function<String, List<String>> parseStringArray =
            s -> Arrays.stream(s.split(";", -1))
                    .map(String::trim)
                    .collect(Collectors.toList());


    public <T> T getValueAt(int i, Function<String, T> parser) {
        return parser.apply(curValues[i]);
    }


    @Override
    public String readLine() throws IOException {
        this.curLine = super.readLine();
        if (curLine != null) {
//            curValues = curLine.split(",", headers.length);
            curValues = curLine.split(",", -1);
            for (int i = 0; i < curValues.length; i++) {
                curValues[i] = curValues[i].trim();
            }
        }
        return curLine;
    }

    public boolean notNull() {
        return curLine != null;
    }

    public int getIntegerAt(int i) {
        if (isSequence[i])
            throw new IllegalArgumentException("Use getIntegerArrayAt instead.");
        return Integer.parseInt(curValues[i].trim());
    }

    public int[] getIntegerArrayAt(int i) {
        if (!isSequence[i])
            throw new IllegalArgumentException("Use getIntegerAt instead.");

        String[] values = curValues[i].split(";", -1);
        if (values.length == 1 && values[0].isEmpty()) {
            return new int[0];
        } else {
            int[] ans = new int[values.length];
            for (int j = 0; j < ans.length; j++)
                ans[j] = Integer.parseInt(values[j].trim());
            return ans;
        }
    }

    public double getDoubleAt(int i) {
        if (isSequence[i])
            throw new IllegalArgumentException("Use getDoubleArrayAt instead.");
        return Double.parseDouble(curValues[i].trim());
    }

    public double[] getDoubleArrayAt(int i) {
        if (!isSequence[i])
            throw new IllegalArgumentException("Use getDoubleAt instead.");

        String[] values = curValues[i].split(";", -1);
        if (values.length == 1 && values[0].isEmpty()) {
            return new double[0];
        } else {
            double[] ans = new double[values.length];
            for (int j = 0; j < ans.length; j++)
                ans[j] = Double.parseDouble(values[j].trim());
            return ans;
        }
    }

    public String getStringAt(int i) {
        if (isSequence[i])
            throw new IllegalArgumentException("Use getStringArrayAt instead.");
        return curValues[i];
    }

    public String[] getStringArrayAt(int i) {
        if (!isSequence[i])
            throw new IllegalArgumentException("Use getStringAt instead.");

        String[] values = curValues[i].split(";", -1);
        if (values.length == 1 && values[0].isEmpty()) {
            return new String[0];
        } else {
            for (int j = 0; j < values.length; j++)
                values[j] = values[j].trim();
            return values;
        }
    }

}

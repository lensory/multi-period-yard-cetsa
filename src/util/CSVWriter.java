package util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;

/**
 * A utility class for writing data to CSV files.
 * Supports headers, sequence fields (collections or arrays), and efficient buffered writing.
 */
public class CSVWriter extends BufferedWriter {
    private final String filename;
    private final String[] headers;
    private final boolean[] isSequence;

    /**
     * Constructor to initialize CsvWriter.
     *
     * @param filename   The name of the file to write.
     * @param headers    The array of column headers.
     * @param isSequence A boolean array indicating whether each field is a sequence.
     * @throws IOException If the file cannot be created or written.
     */
    public CSVWriter(String filename, String[] headers, boolean[] isSequence) throws IOException {
        super(new FileWriter(filename));

        // Validate that headers and isSequence have the same length
        if (headers.length != isSequence.length) {
            throw new IllegalArgumentException(
                    String.format(
                            "The lengths of 'headers' (%d) and 'isSequence' (%d) must be the same.",
                            headers.length, isSequence.length
                    )
            );
        }

        // Validate that headers are not empty
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] == null || headers[i].isEmpty()) {
                throw new IllegalArgumentException(
                        String.format("Header at index %d cannot be null or empty.", i)
                );
            }
        }

        this.filename = filename;
        this.headers = Arrays.copyOf(headers, headers.length);
        this.isSequence = Arrays.copyOf(isSequence, isSequence.length);

        // Write the header row
        for (int i = 0; i < headers.length; i++) {
            super.write(headers[i]);
            if (i < headers.length - 1) {
                super.write(',');
            }
        }
        super.newLine();

    }

    /**
     * Constructor to initialize CsvWriter.
     *
     * @param filename The name of the file to write.
     * @param headers  The array of column headers.
     * @throws IOException If the file cannot be created or written.
     */
    public CSVWriter(String filename, String[] headers) throws IOException {
        this(filename, headers, new boolean[headers.length]);
    }

    /**
     * Writes a row of data to the CSV file.
     *
     * @param data The data values (varargs).
     * @throws IllegalArgumentException If the number of fields does not match the headers,
     *                                  or if any required field is null.
     */
    public void writeLine(Object... data) {
        if (data == null || data.length == 0)
            throw new IllegalArgumentException("Data cannot be null.");
        if (data.length != headers.length) {
            throw new IllegalArgumentException(
                    String.format("Expected %d fields, but got %d fields.", headers.length, data.length)
            );
        }
        try {
            for (int i = 0; i < data.length; i++) {
                Object value = data[i];
                if (value == null)
                    throw new IllegalArgumentException(
                            String.format("Field '%s' cannot be null.", headers[i])
                    );
                // If the field is a sequence, process it as a semicolon-separated string
                if (isSequence[i]) {
                    if (value instanceof Collection) {
                        writeSequence(((Collection<?>) value).iterator());
                    } else if (value.getClass().isArray()) {
                        writeSequence(Arrays.stream(toObjectArray(value)).iterator());
                    } else {
                        throw new IllegalArgumentException(
                                String.format("Field '%s' is marked as a sequence but is not a Collection.", headers[i])
                        );
                    }
                } else {
                    // For non-sequence fields, write the value directly
                    super.write(value.toString());
                }

                if (i < data.length - 1) {
                    super.write(','); // Write the comma separator
                }
            }
            super.newLine(); // Write the newline character
        } catch (IOException e) {
            String errorDetail = String.format(
                    "CSV write failure [File: %s]\nHeaders: %s\nSequence flags: %s\nCurrent data: %s",
                    filename,
                    Arrays.toString(headers),
                    Arrays.toString(isSequence),
                    Arrays.deepToString(data)
            );
            throw new RuntimeException(errorDetail, e);
        }
    }

    /**
     * Writes a sequence of elements separated by a semicolon.
     *
     * @param iterator The iterator over the sequence elements.
     * @throws IOException              If writing fails.
     * @throws IllegalArgumentException If any element in the sequence is null.
     */
    private void writeSequence(java.util.Iterator<?> iterator) throws IOException {
        boolean first = true;
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (item == null) {
                throw new IllegalArgumentException("Element in sequence cannot be null.");
            }
            if (!first) {
                super.write(';'); // Write the semicolon separator
            }
            super.write(item.toString());
            first = false;
        }
    }


    /**
     * Converts a primitive or object array to an Object array.
     *
     * @param array The array to convert.
     * @return An Object array.
     * @throws IllegalArgumentException If the input is not an array.
     */
    private Object[] toObjectArray(Object array) {
        if (array == null) {
            return null;
        }
        if (array instanceof Object[]) {
            return (Object[]) array;
        }
        if (!array.getClass().isArray()) {
            throw new IllegalArgumentException("Input is not an array: " + array.getClass());
        }

        int length = Array.getLength(array);
        Object[] result = new Object[length];
        for (int i = 0; i < length; i++) {
            result[i] = Array.get(array, i);
        }
        return result;
    }


}

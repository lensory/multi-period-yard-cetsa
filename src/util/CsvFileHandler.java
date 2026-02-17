package util;

import java.io.IOException;
import java.nio.file.Path;


public class CsvFileHandler<T> {
    private final String fileName;
    private final String[] headers;
    private final boolean[] isSequenceFlags;
    private final BiThrowableConsumer<T, CSVReader> reader;
    private final BiThrowableConsumer<T, CSVWriter> writer;

    public CsvFileHandler(String fileName, String[] headers, boolean[] isSequenceFlags,
                          BiThrowableConsumer<T, CSVReader> reader,
                          BiThrowableConsumer<T, CSVWriter> writer) {
        this.fileName = fileName;
        this.headers = headers;
        this.isSequenceFlags = isSequenceFlags;
        this.reader = reader;
        this.writer = writer;
    }

    public CsvFileHandler(String fileName, String[] headers, BiThrowableConsumer<T, CSVReader> reader, BiThrowableConsumer<T, CSVWriter> writer) {
        this(fileName, headers, new boolean[headers.length], reader, writer);
    }

    public void read(T target, Path dir) throws IOException {
        try (CSVReader cr = new CSVReader(dir.resolve(fileName).toString(), headers, isSequenceFlags)) {
            reader.accept(target, cr);
        }
    }

    public void write(T target, Path dir) throws IOException {
        try (CSVWriter csv = new CSVWriter(dir.resolve(fileName).toString(), headers, isSequenceFlags)) {
            writer.accept(target, csv);
        }
    }
}

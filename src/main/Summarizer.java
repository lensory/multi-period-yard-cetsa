package main;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

public class Summarizer {

    private static final String OVERALL_SOLUTION_FILE = "overallSolution.csv";


    private Path summaryPath;
    private Set<String> allDataHeaders = new HashSet<>();
    private List<String> allHeaders = new ArrayList<>();

    public void summarize(String outputDir, String summaryFile) {
        this.summaryPath = Paths.get(summaryFile);

        List<Path> instanceDirs = new LinkedList<>();
        try (var pathsStream = Files.list(Paths.get(outputDir))) {
            instanceDirs = pathsStream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 收集所有数据列名
        allDataHeaders.clear();
        for (Path instanceDir : instanceDirs) {
            Path overallSolutionPath = instanceDir.resolve(OVERALL_SOLUTION_FILE);
            if (Files.exists(overallSolutionPath)) {
                try {
                    List<String> lines = Files.readAllLines(overallSolutionPath);
                    if (!lines.isEmpty()) {
                        String headerLine = lines.get(0);
                        String[] currentHeaders = headerLine.split(",");
                        allDataHeaders.addAll(Arrays.asList(currentHeaders));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        // 合并所有列名到allHeaders
        allHeaders.clear();
        allHeaders.addAll(Arrays.asList("instance", "ships", "yard", "seed", "solver", "testTime"));
        allHeaders.addAll(allDataHeaders);

        try (BufferedWriter writer = Files.newBufferedWriter(
                this.summaryPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            // 写入标题行
            writer.write(String.join(",", allHeaders));
            writer.newLine();

            // 写入数据行
            for (Path instanceDir : instanceDirs) {
                summarizeData(instanceDir, writer);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Summary written to " + summaryFile);
    }

    private void summarizeData(Path instanceDir, BufferedWriter writer) {
        Path overallSolutionPath = instanceDir.resolve(OVERALL_SOLUTION_FILE);
        if (Files.exists(overallSolutionPath)) {
            try {
                List<String> lines = Files.readAllLines(overallSolutionPath);
                if (lines.size() > 1) {
                    String fileName = instanceDir.getFileName().toString();
                    String[] parts = fileName.split("_");
                    String ships = "";
                    String yard = "";
                    String seed = "";
                    String solver = "";
                    String testTime = "";

                    if (parts.length == 4) { // 旧格式
                        ships = parts[1].replace("{", "").replace("}", "");
                        yard = parts[2].replace("{", "").replace("}", "");
                        String[] numbers = yard.split("-");
                        String formattedFirst = String.format("%02d", Integer.parseInt(numbers[0]));
                        String formattedSecond = String.format("%02d", Integer.parseInt(numbers[1]));
                        yard = formattedFirst + "-" + formattedSecond;
                        seed = parts[3];
                    } else if (parts.length == 6) { // 新格式
                        ships = parts[1].replace("{", "").replace("}", "");
                        yard = parts[2].replace("{", "").replace("}", "");
                        String[] numbers = yard.split("-");
                        String formattedFirst = String.format("%02d", Integer.parseInt(numbers[0]));
                        String formattedSecond = String.format("%02d", Integer.parseInt(numbers[1]));
                        yard = formattedFirst + "-" + formattedSecond;
                        seed = parts[3];
                        solver = parts[4];
                        testTime = parts[5];
                    }

                    String dataLine = lines.get(1);
                    String[] dataValues = dataLine.split(",");
                    String[] headers = lines.get(0).split(",");
                    Map<String, String> dataMap = new HashMap<>();
                    for (int i = 0; i < headers.length; i++) {
                        dataMap.put(headers[i], dataValues[i]);
                    }

                    List<String> rowValues = new ArrayList<>();
                    rowValues.add(fileName); // instance
                    rowValues.add(ships);
                    rowValues.add(yard);
                    rowValues.add(seed);
                    rowValues.add(solver);
                    rowValues.add(testTime);

                    for (String header : allHeaders.subList(6, allHeaders.size())) {
                        rowValues.add(dataMap.getOrDefault(header, ""));
                    }

                    writer.write(String.join(",", rowValues));
                    writer.newLine();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java org.example.Summarizer <output_directory> <summary_file>");
            return;
        }

        new Summarizer().summarize(args[0], args[1]);
    }
}
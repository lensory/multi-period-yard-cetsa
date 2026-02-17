package main;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CplexLogParser {

    // 匹配节点信息行的正则表达式 - 标准格式
    private static final Pattern NODE_PATTERN = Pattern.compile(
            "\\s*(\\d+)\\s+(\\d+)\\s+([\\d\\.e+-]+|cutoff)\\s+(\\d+)\\s+([\\d\\.e+-]+|)\\s+([\\d\\.e+-]+|Cuts:\\s*\\d+)\\s+(\\d+)\\s+([\\d\\.]+%?)"
    );

    // 匹配节点信息行的正则表达式 - 0+ 格式
    private static final Pattern NODE_PATTERN_PLUS = Pattern.compile(
            "\\s*(\\d+)\\+\\s+(\\d+)\\s+([\\d\\.e+-]+)\\s+([\\d\\.e+-]+)\\s+([\\d\\.]+%?)"
    );

    // 匹配节点信息行的正则表达式 - cutoff 格式
    private static final Pattern NODE_PATTERN_CUTOFF = Pattern.compile(
            "\\s*(\\d+)\\s+(\\d+)\\s+cutoff\\s+([\\d\\.e+-]+)\\s+([\\d\\.e+-]+)\\s+(\\d+)\\s+([\\d\\.]+%?)"
    );

    public static void main(String[] args) {
        // 指定日志目录路径
        String logDirectoryPath = "D:\\liuyu\\IdeaProjects\\apjor-yard-maven-back\\linux\\log";

        try {
            processLogs(logDirectoryPath);
        } catch (IOException e) {
            System.err.println("处理日志文件时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理指定目录下的所有日志文件
     */
    public static void processLogs(String directoryPath) throws IOException {
        Path dir = Paths.get(directoryPath);
        if (!Files.exists(dir)) {
            System.err.println("指定目录不存在: " + directoryPath);
            return;
        }

        // 查找所有匹配模式的cplexIntegrated日志文件
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().contains("cplexIntegrated"))
                    .filter(path -> path.toString().endsWith(".log"))
                    .forEach(CplexLogParser::parseLogFile);
        }
    }

    /**
     * 解析单个日志文件
     */
    public static void parseLogFile(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);

            // 从文件名提取算例名称
            String fileName = logFile.getFileName().toString();
            String instanceName = fileName.replace("config_", "")
                    .replaceAll("_\\d{8}T\\d+\\.log", "");

            // 逆序查找最后两个符合格式的节点行
            List<String> nodeLines = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && nodeLines.size() < 2; i--) {
                String line = lines.get(i);
                if (NODE_PATTERN.matcher(line).matches() ||
                        NODE_PATTERN_PLUS.matcher(line).matches() ||
                        NODE_PATTERN_CUTOFF.matcher(line).matches()) {
                    nodeLines.add(0, line); // 添加到列表开头保持顺序
                }
            }

            // 打印结果
            if (nodeLines.size() == 2) {
                System.out.println("算例名称: " + instanceName);
                for (String line : nodeLines) {
                    System.out.println(line);

                    // 解析并显示各列含义
                    printNodeInfo(line);
                }
                System.out.println(); // 空行分隔不同算例
            } else {
                System.out.println("算例 " + instanceName + ": 未找到足够的节点信息行");
            }

        } catch (IOException e) {
            System.err.println("读取文件失败: " + logFile.toString() + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 解析并打印节点信息
     */
    private static void printNodeInfo(String line) {
        // 标准格式匹配
        Matcher standardMatcher = NODE_PATTERN.matcher(line);
        if (standardMatcher.matches()) {
            System.out.printf("  Node=%s, NodesLeft=%s, Objective=%s, IInf=%s, " +
                            "BestInteger=%s, BestBound/Cuts=%s, ItCnt=%s, Gap=%s%n",
                    standardMatcher.group(1), standardMatcher.group(2), standardMatcher.group(3),
                    standardMatcher.group(4), standardMatcher.group(5), standardMatcher.group(6),
                    standardMatcher.group(7), standardMatcher.group(8));
            return;
        }

        // 0+ 格式匹配
        Matcher plusMatcher = NODE_PATTERN_PLUS.matcher(line);
        if (plusMatcher.matches()) {
            System.out.printf("  Node=%s+, NodesLeft=%s, BestInteger=%s, BestBound=%s, Gap=%s%n",
                    plusMatcher.group(1), plusMatcher.group(2), plusMatcher.group(3),
                    plusMatcher.group(4), plusMatcher.group(5));
            return;
        }

        // cutoff 格式匹配
        Matcher cutoffMatcher = NODE_PATTERN_CUTOFF.matcher(line);
        if (cutoffMatcher.matches()) {
            System.out.printf("  Node=%s, NodesLeft=%s, Objective=cutoff, " +
                            "BestInteger=%s, BestBound=%s, ItCnt=%s, Gap=%s%n",
                    cutoffMatcher.group(1), cutoffMatcher.group(2), cutoffMatcher.group(3),
                    cutoffMatcher.group(4), cutoffMatcher.group(5), cutoffMatcher.group(6));
            return;
        }
    }
}
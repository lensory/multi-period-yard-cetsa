package main;

import entity.Instance;
import entity.Solution;
import ilog.concert.IloException;
import ilog.cplex.IloCplex;
import solver.CplexOriginalModel;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Checker {// 用于存储验证结果的类

    public static class ValidationResult {
        public String instanceName;
        public String solverName;
        public String timestamp;
        public boolean validationPassed;

        public boolean cplexSolved;
        public String cplexStatus;

        public double objectiveDifference;
        public String error = "";

        // 原始解的目标函数值
        public double originalObjAll;
        public double originalObjRoute;
        public double originalObjTime;
        public double originalObjCongestion;

        // 模型解的目标函数值
        public double modelObjAll;
        public double modelObjRoute;
        public double modelObjTime;
        public double modelObjCongestion;

        public ValidationResult(String instanceName, String solverName, String timestamp) {
            this.instanceName = instanceName;
            this.solverName = solverName;
            this.timestamp = timestamp;
        }
    }

    public static void processAllSolutions(String outputDir, Set<String> limitedInstances) throws IOException, IloException {
        Path outputDirPath = Paths.get(outputDir);
        if (!Files.isDirectory(outputDirPath)) {
            System.err.println("Output directory does not exist: " + outputDir);
            return;
        }

        // 匹配 solution 文件夹的模式
        // 格式: solution_{02-00-01}_{06-01}_01_cplexIntegrated_20250831T163527958
        // 捕获组: 1=instanceName, 2=solverName, 3=timestamp
        Pattern solutionPattern = Pattern.compile(
                "solution_(\\{\\d{2}-\\d{2}-\\d{2}}_\\{\\d{2}-\\d{2}}_\\d{2})_([^_]+)_(.+)");

        List<ValidationResult> results = new ArrayList<>();

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(outputDirPath)) {
            for (Path solutionPath : directoryStream) {
                if (Files.isDirectory(solutionPath)) {
                    String solutionFolderName = solutionPath.getFileName().toString();
                    Matcher matcher = solutionPattern.matcher(solutionFolderName);

                    if (matcher.matches()) {
                        String instanceName = matcher.group(1);
                        String solverName = matcher.group(2);
                        String timestamp = matcher.group(3);

                        if (limitedInstances != null) {
                            boolean matched = false;
                            for (String regex : limitedInstances) {
                                Pattern pattern = Pattern.compile(regex);
                                if (pattern.matcher(solutionFolderName).matches()) {
                                    matched = true;
                                    break;
                                }
                            }
                            if (!matched) {
                                continue;
                            }
                        }

//                        if (limitedInstances != null && !limitedInstances.contains(instanceName.substring(0, 10))) {
//                            continue;
//                        }


                        String instanceFileName = "input/instance_" + instanceName + ".json";
                        String solutionPathStr = solutionPath.toString();

                        System.out.println("Processing: " + solutionFolderName);
                        System.out.println("Instance: " + instanceName);
                        System.out.println("Solver: " + solverName);
                        System.out.println("Timestamp: " + timestamp);
                        System.out.println("Instance file: " + instanceFileName);

                        try {
                            ValidationResult result = checkSolution(instanceFileName, solutionPathStr, instanceName, solverName, timestamp);
                            results.add(result);
                        } catch (Exception e) {
                            System.err.println("Error processing " + solutionFolderName + ": " + e.getMessage());
                            e.printStackTrace();

                            ValidationResult result = new ValidationResult(instanceName, solverName, timestamp);
                            result.error = e.getMessage();
                            results.add(result);
                        }

                        System.out.println("-".repeat(100));
                    } else {
                        System.out.println("Skipping folder (doesn't match pattern): " + solutionFolderName);
                    }
                }
            }
        }
        // 输出CSV格式的汇总报告
        outputCsvSummary(results);
    }

    public static ValidationResult checkSolution(String instanceFilePath, String solutionDirPath,
                                                 String instanceName, String solverName, String timestamp) {
        ValidationResult result = new ValidationResult(instanceName, solverName, timestamp);
        try {
            // 检查 instance 文件是否存在
            if (!Files.exists(Paths.get(instanceFilePath))) {
                System.err.println("Instance file does not exist: " + instanceFilePath);
                result.error += "Instance file does not exist\n";
                return result;
            }

            // 读取 instance 和 solution
            Instance instance = Instance.readJson(instanceFilePath);
            Solution solution = new Solution(instance);
            solution.read(solutionDirPath);

            // 验证 solution
            solution.calculateObjectives();
            solution.validate();

            System.out.println("Solution validation passed.");
            System.out.println("Original solution objectives: " + solution.briefObjectives());

            result.validationPassed = true;
            result.originalObjAll = solution.getObjAll();
            result.originalObjRoute = solution.getObjRoute();
            result.originalObjTime = solution.getObjTime();
            result.originalObjCongestion = solution.getObjCongestion();

            // 使用 CPLEX 模型验证
            try (IloCplex cplex = new IloCplex()) {
                // 设置参数以提高求解速度
                cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, 0.0);
//                cplex.setParam(IloCplex.Param.TimeLimit, 120.0); // 2分钟时间限制

                CplexOriginalModel model = CplexOriginalModel.buildOriginalIntegratedModel(instance, cplex);
                model.fixKeyVariables(solution);

                boolean solved = model.solve();
                System.out.println("CPLEX solved: " + solved);
                result.cplexSolved = solved;

                if (solved) {
                    System.out.println("CPLEX status: " + cplex.getStatus());
                    result.cplexStatus = cplex.getStatus().toString();
                    Solution modelSolution = model.getSolution();
                    if (modelSolution != null) {
                        System.out.println("Model solution objectives: " + modelSolution.briefObjectives());

                        // 存储模型解的目标函数值
                        result.modelObjAll = modelSolution.getObjAll();
                        result.modelObjRoute = modelSolution.getObjRoute();
                        result.modelObjTime = modelSolution.getObjTime();
                        result.modelObjCongestion = modelSolution.getObjCongestion();

                        // 比较目标函数值
                        double originalObj = solution.getObjAll();
                        double modelObj = modelSolution.getObjAll();
                        double diff = Math.abs(originalObj - modelObj);

                        System.out.println("Objective difference: " + diff);
                        result.objectiveDifference = diff;
                        if (diff > 1e-6) {
                            System.out.println("WARNING: Objective values differ significantly!");
                        }
                    } else {
                        System.out.println("Model returned null solution");
                        result.error += "Model returned null solution\n";
                    }
                } else {
                    System.out.println("WARNING: CPLEX could not solve the fixed problem");
                    System.out.println("CPLEX status: " + cplex.getStatus());
                    result.cplexStatus = cplex.getStatus().toString();
                    result.error += "CPLEX could not solve the fixed problem\n";
                }
            }
        } catch (Exception e) {
            System.err.println("Error checking solution for " + instanceFilePath + ": " + e.getMessage());
            e.printStackTrace();
            result.error += e.getMessage() + "\n";
        }
        return result;
    }

    public static void outputCsvSummary(List<ValidationResult> results) {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("CSV SUMMARY REPORT");
        System.out.println("=".repeat(100));

        // 输出CSV头部
        System.out.println("Instance Name,\tSolver Name,\tTimestamp,\tValidation Passed,\tCPLEX Solved,\tCPLEX Status," +
                "\tObjective Difference,\tError,\tOriginal Objectives,\tModel Objectives");

        // 输出每一行数据
        for (ValidationResult result : results) {
            String line = String.format("%s,\t%s,\t%s,\t%s,\t%s,\t%s,\t%f,\t%s," +
                            "\t%f,\t%f,\t%f,\t%f,\t%f,\t%f,\t%f,\t%f",
                    result.instanceName,
                    result.solverName,
                    result.timestamp,
                    result.validationPassed,
                    result.cplexSolved,
                    result.cplexStatus != null ? result.cplexStatus : "",
                    result.objectiveDifference,
                    result.error,
                    result.originalObjAll,
                    result.originalObjRoute,
                    result.originalObjTime,
                    result.originalObjCongestion,
                    result.modelObjAll,
                    result.modelObjRoute,
                    result.modelObjTime,
                    result.modelObjCongestion
            );
            System.out.println(line);
        }

        System.out.println("=".repeat(100));
        System.out.println("Total solutions processed: " + results.size());
        System.out.println("=".repeat(100));
    }

    public static void main(String[] args) {

        // 匹配 solution 文件夹的模式
        // 格式: solution_{02-00-01}_{06-01}_01_cplexIntegrated_20250831T163527958
        Set<String> matchingRegex = Set.of("solution_\\{16-04-04}_\\{.*}_01_decomposedRandom_20250710.*");
        try {
            processAllSolutions("linux/output", matchingRegex);
        } catch (IOException | IloException e) {
            e.printStackTrace();
        }

    }
}

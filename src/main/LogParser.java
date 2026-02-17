package main;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用于解析和结构化log文件的类
 */
public class LogParser {

    // 定义用于匹配log中关键信息的正则表达式
    private static final Pattern INTEGRATED_SOLUTION_PATTERN = Pattern.compile("(obj=[\\d.]+ \\[route=[\\d.]+, time=[\\d.]+, congestion=[\\d.]+])");

    private static final Pattern INSTANCE_PATTERN = Pattern.compile("Start to solve instance (.+?) by (.+)");

    private static final Pattern ATTEMPT_PATTERN = Pattern.compile("Attempt (\\d+): Initial Solution by (.+?): " + INTEGRATED_SOLUTION_PATTERN + ", Elapsed time = ([\\d.]+) sec, Evaluated solutions = (\\d+)\\.");
    private static final Pattern NO_INITIAL_SOLUTION_PATTERN = Pattern.compile(
            "Attempt (\\d+): No Initial Solution Found by Heuristic, Elapsed time = ([\\d.]+) sec, Evaluated solutions = (\\d+)\\."
    );

    private static final Pattern NEIGHBOR_COUNT_PATTERN = Pattern.compile("(\\d+)\\s+Number of neighbors to be explored: (\\d+)");
    private static final Pattern NO_FEASIBLE_SOLUTION_PATTERN = Pattern.compile(
            "(\\d+)\\s+No Feasible Solution in Current Solution's Neighborhood \\((\\d+)\\[<=(\\d+)]\\)\\."
    );
    private static final Pattern NEIGHBOR_ITERATION_PATTERN = Pattern.compile("(\\d+)\\s+([*+-]+)\\s+Neighbor Solution: " + INTEGRATED_SOLUTION_PATTERN + ", Elapsed time = ([\\d.]+) sec, Evaluated solutions = (\\d+)\\.");
    private static final Pattern NEIGHBOR_END_PATTERN = Pattern.compile("Neighborhood Search Ends With Best Solution: " + INTEGRATED_SOLUTION_PATTERN);
    private static final Pattern NEIGHBOR_IMPROVE_PATTERN = Pattern.compile("Improve ([\\d.]+) % from " + INTEGRATED_SOLUTION_PATTERN);

    private static final Pattern LOCAL_REFINEMENT_ITERATION_PATTERN = Pattern.compile("(OptGiven[K|T])\\s*\\+{3} Local Refined Solution: " + INTEGRATED_SOLUTION_PATTERN + ", Elapsed time = ([\\d.]+) sec, Evaluated solutions = (\\d+)\\.");
    private static final Pattern LOCAL_REFINEMENT_END_PATTERN = Pattern.compile("Local Refinement Ends With Best Solution: " + INTEGRATED_SOLUTION_PATTERN);
    private static final Pattern LOCAL_REFINEMENT_IMPROVE_PATTERN = Pattern.compile("Improve ([\\d.]+) % from current solution\\(" + INTEGRATED_SOLUTION_PATTERN + "\\)");

    private static final Pattern SHAKE_PATTERN = Pattern.compile("Shake (\\d+) Ends With: " + INTEGRATED_SOLUTION_PATTERN);
    private static final Pattern SHAKE_IMPROVE_PATTERN = Pattern.compile("Improve ([\\d.]+) % from " + INTEGRATED_SOLUTION_PATTERN);
    private static final Pattern SHAKE_END_PATTERN = Pattern.compile("Elapsed Time = ([\\d.]+) sec, Evaluated solutions = (\\d+)\\."); // e.g., Elapsed Time = 118.44 sec, Evaluated solutions = 1620.
    private static final Pattern MAXIMAL_SHAKE_ATTEMPTS_PATTERN = Pattern.compile(
            "Reached the maximal shake attempts for new Priority \\((\\d+)\\)"
    );
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("----------------------------------------------------------------------------------------------------");

    private static final Pattern SUMMARY_HEAD_PATTERN = Pattern.compile("Brief Search Log:\\s*");
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
            "Summary:\\s*" +
                    "Initial Solution: " + INTEGRATED_SOLUTION_PATTERN + "\\s*" +
                    "Best Solution: " + INTEGRATED_SOLUTION_PATTERN + "\\s*" +
                    "Improvement: ([\\d.]+) %\\s*" +
                    "Solution found: " + INTEGRATED_SOLUTION_PATTERN + "\\s*" +
                    "Vessels=\\((\\d+), (\\d+), (\\d+)\\), Yard=\\((\\d+), (\\d+)\\), Seed=(\\d+): " +
                    INTEGRATED_SOLUTION_PATTERN +
                    ", runningTime=([\\d.]+)s\\s*"
    );

    /**
     * 表示一次完整的求解循环
     */
    public static class SolveCycle {
        private int shakeNumber;

        private double initialSolutionElapsedTime;


        private int initialSolutionEvaluatedSolutions;
        private TemporarySolution initialTemporarySolution;

        private List<NeighborImprovement> neighborImprovements;
        private TemporarySolution neighborhoodResult;

        private List<LocalRefinementStep> localRefinementSteps;
        private TemporarySolution localRefinementResult;

        private double improvementPercentage;
        private double elapsedTime;
        private int evaluatedSolutions;
        private int shakeNewPriorityTimes;

        public SolveCycle() {
            this.localRefinementSteps = new ArrayList<>();
            this.neighborImprovements = new ArrayList<>();
        }

        public void setShakeNewPriorityTimes(int shakeNewPriorityTimes) {
            this.shakeNewPriorityTimes = shakeNewPriorityTimes;
        }

        public int getShakeNewPriorityTimes() {
            return shakeNewPriorityTimes;
        }

        // Getters and setters
        public int getShakeNumber() {
            return shakeNumber;
        }

        public void setShakeNumber(int shakeNumber) {
            this.shakeNumber = shakeNumber;
        }

        public TemporarySolution getInitialSolution() {
            return initialTemporarySolution;
        }

        public void setInitialSolution(TemporarySolution initialTemporarySolution) {
            this.initialTemporarySolution = initialTemporarySolution;
        }

        public double getInitialSolutionElapsedTime() {
            return initialSolutionElapsedTime;
        }

        public void setInitialSolutionElapsedTime(double initialSolutionElapsedTime) {
            this.initialSolutionElapsedTime = initialSolutionElapsedTime;
        }

        public int getInitialSolutionEvaluatedSolutions() {
            return initialSolutionEvaluatedSolutions;
        }

        public void setInitialSolutionEvaluatedSolutions(int initialSolutionEvaluatedSolutions) {
            this.initialSolutionEvaluatedSolutions = initialSolutionEvaluatedSolutions;
        }

        public TemporarySolution getNeighborhoodSolution() {
            return neighborhoodResult;
        }

        public void setNeighborhoodSolution(TemporarySolution neighborhoodTemporarySolution) {
            this.neighborhoodResult = neighborhoodTemporarySolution;
        }

        public List<NeighborImprovement> getNeighborImprovements() {
            return neighborImprovements;
        }

        public void setNeighborImprovements(List<NeighborImprovement> neighborImprovements) {
            this.neighborImprovements = neighborImprovements;
        }

        public List<LocalRefinementStep> getLocalRefinementSteps() {
            return localRefinementSteps;
        }

        public void setLocalRefinementSteps(List<LocalRefinementStep> localRefinementSteps) {
            this.localRefinementSteps = localRefinementSteps;
        }

        public TemporarySolution getLocalRefinementResult() {
            return localRefinementResult;
        }

        public void setLocalRefinementResult(TemporarySolution localRefinementResult) {
            this.localRefinementResult = localRefinementResult;
        }

        public double getImprovementPercentage() {
            return improvementPercentage;
        }

        public void setImprovementPercentage(double improvementPercentage) {
            this.improvementPercentage = improvementPercentage;
        }

        public double getElapsedTime() {
            return elapsedTime;
        }

        public void setElapsedTime(double elapsedTime) {
            this.elapsedTime = elapsedTime;
        }

        public int getEvaluatedSolutions() {
            return evaluatedSolutions;
        }

        public void setEvaluatedSolutions(int evaluatedSolutions) {
            this.evaluatedSolutions = evaluatedSolutions;
        }

        public void summary() {
            System.out.println("Cycle: " + this.getShakeNumber());
            System.out.println("Initial Solution: " + this.getInitialSolution() + "\tTime=" + this.getInitialSolutionElapsedTime() + "\tEval=" + this.getInitialSolutionEvaluatedSolutions());
            for (LogParser.NeighborImprovement improvement : this.getNeighborImprovements())
                System.out.println("Neighbor Improvement Step: " + improvement.getSolution() + "\tTime=" + improvement.getElapsedTime() + "\tEval=" + improvement.getEvaluatedSolutions());
            for (LogParser.LocalRefinementStep step : this.getLocalRefinementSteps())
                System.out.println("Local Refinement Step: " + step.getSolution() + "\tTime=" + step.getElapsedTime() + "\tEval=" + step.getEvaluatedSolutions());
            System.out.println("Shake " + this.getShakeNumber() +
                    " - Improvement: " + this.getImprovementPercentage() + "%");
        }

    }

    /**
     * 表示一个解
     */
    public static class TemporarySolution {
        private static final Pattern SOLUTION_PATTERN = Pattern.compile("obj=([\\d.]+) \\[route=([\\d.]+), time=([\\d.]+), congestion=([\\d.]+)]");
        private static final String SOLUTION_FORMAT = "obj=%.8f [route=%.2f, time=%.2f, congestion=%.2f]";
        private double objective;
        private double route;
        private double time;
        private double congestion;

        public TemporarySolution() {
        }

        public TemporarySolution(double objective, double route, double time, double congestion) {
            this.objective = objective;
            this.route = route;
            this.time = time;
            this.congestion = congestion;
        }

        public TemporarySolution(String str) {
            Matcher matcher = SOLUTION_PATTERN.matcher(str);
            if (matcher.find()) {
                this.objective = Double.parseDouble(matcher.group(1));
                this.route = Double.parseDouble(matcher.group(2));
                this.time = Double.parseDouble(matcher.group(3));
                this.congestion = Double.parseDouble(matcher.group(4));
            } else {
                throw new IllegalArgumentException("Invalid string format: " + str);
            }
        }

        // Getters and setters
        public double getObjective() {
            return objective;
        }

        public void setObjective(double objective) {
            this.objective = objective;
        }

        public double getRoute() {
            return route;
        }

        public void setRoute(double route) {
            this.route = route;
        }

        public double getTime() {
            return time;
        }

        public void setTime(double time) {
            this.time = time;
        }

        public double getCongestion() {
            return congestion;
        }

        public void setCongestion(double congestion) {
            this.congestion = congestion;
        }

        public String toString() {
            return String.format("obj=%.8f [route=%.2f, time=%.2f, congestion=%.2f]",
                    objective, route, time, congestion);
        }
    }

    /**
     * 表示局部优化步骤
     */
    public static class LocalRefinementStep {
        private String type; // OptGivenK or OptGivenT
        private TemporarySolution temporarySolution;
        private double elapsedTime;
        private int evaluatedSolutions;

        public LocalRefinementStep() {
        }

        public LocalRefinementStep(String type, TemporarySolution temporarySolution, double elapsedTime, int evaluatedSolutions) {
            this.type = type;
            this.temporarySolution = temporarySolution;
            this.elapsedTime = elapsedTime;
            this.evaluatedSolutions = evaluatedSolutions;
        }

        // Getters and setters
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public TemporarySolution getSolution() {
            return temporarySolution;
        }

        public void setSolution(TemporarySolution temporarySolution) {
            this.temporarySolution = temporarySolution;
        }

        public double getElapsedTime() {
            return elapsedTime;
        }

        public void setElapsedTime(double elapsedTime) {
            this.elapsedTime = elapsedTime;
        }

        public int getEvaluatedSolutions() {
            return evaluatedSolutions;
        }

        public void setEvaluatedSolutions(int evaluatedSolutions) {
            this.evaluatedSolutions = evaluatedSolutions;
        }
    }

    public static class NeighborImprovement {
        private int iteration;
        private String improvementType; // "***" 表示全局最优改进，"+++" 表示局部改进
        private TemporarySolution temporarySolution;
        private double elapsedTime;
        private int evaluatedSolutions;

        public NeighborImprovement() {
        }

        public NeighborImprovement(int iteration, String improvementType, TemporarySolution temporarySolution, double elapsedTime, int evaluatedSolutions) {
            this.iteration = iteration;
            this.improvementType = improvementType;
            this.temporarySolution = temporarySolution;
            this.elapsedTime = elapsedTime;
            this.evaluatedSolutions = evaluatedSolutions;
        }

        // Getters and setters
        public int getIteration() {
            return iteration;
        }

        public void setIteration(int iteration) {
            this.iteration = iteration;
        }

        public String getImprovementType() {
            return improvementType;
        }

        public void setImprovementType(String improvementType) {
            this.improvementType = improvementType;
        }

        public TemporarySolution getSolution() {
            return temporarySolution;
        }

        public void setSolution(TemporarySolution temporarySolution) {
            this.temporarySolution = temporarySolution;
        }

        public double getElapsedTime() {
            return elapsedTime;
        }

        public void setElapsedTime(double elapsedTime) {
            this.elapsedTime = elapsedTime;
        }

        public int getEvaluatedSolutions() {
            return evaluatedSolutions;
        }

        public void setEvaluatedSolutions(int evaluatedSolutions) {
            this.evaluatedSolutions = evaluatedSolutions;
        }
    }

    /**
     * 表示整个log文件的摘要信息
     */
    public static class LogSummary {
        private String instanceName;
        private String method;
        private TemporarySolution initialTemporarySolution;
        private TemporarySolution bestTemporarySolution;
        private double improvementPercentage;
        private double totalTime;
        private int totalEvaluatedSolutions;

        // Getters and setters
        public String getInstanceName() {
            return instanceName;
        }

        public void setInstanceName(String instanceName) {
            this.instanceName = instanceName;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public TemporarySolution getInitialSolution() {
            return initialTemporarySolution;
        }

        public void setInitialSolution(TemporarySolution initialTemporarySolution) {
            this.initialTemporarySolution = initialTemporarySolution;
        }

        public TemporarySolution getBestSolution() {
            return bestTemporarySolution;
        }

        public void setBestSolution(TemporarySolution bestTemporarySolution) {
            this.bestTemporarySolution = bestTemporarySolution;
        }

        public double getImprovementPercentage() {
            return improvementPercentage;
        }

        public void setImprovementPercentage(double improvementPercentage) {
            this.improvementPercentage = improvementPercentage;
        }

        public double getTotalTime() {
            return totalTime;
        }

        public void setTotalTime(double totalTime) {
            this.totalTime = totalTime;
        }

        public int getTotalEvaluatedSolutions() {
            return totalEvaluatedSolutions;
        }

        public void setTotalEvaluatedSolutions(int totalEvaluatedSolutions) {
            this.totalEvaluatedSolutions = totalEvaluatedSolutions;
        }
    }


    public static class ParsedLog {
        private LogSummary summary;
        private List<SolveCycle> cycles;

        public ParsedLog() {
            this.cycles = new ArrayList<>();
        }

        public LogSummary getSummary() {
            return summary;
        }

        public void setSummary(LogSummary summary) {
            this.summary = summary;
        }

        public List<SolveCycle> getCycles() {
            return cycles;
        }

        public void setCycles(List<SolveCycle> cycles) {
            this.cycles = cycles;
        }
    }


    /**
     * 解析log文件
     *
     * @param filePath log文件路径
     * @return 解析结果
     * @throws IOException 文件读取异常
     */
    public static ParsedLog parseLogFile(String filePath) {
        ParsedLog parsedLog = new ParsedLog();
        List<SolveCycle> cycles = new ArrayList<>();
        int lineCount = 0;
        String line = null;
        enum ParseState {
            EXPECTING_METADATA,
            EXPECTING_INITIAL_SOLUTION,// 期望初始解
            EXPECTING_NS_LR_S,
            EXPECTING_LR_S,
            EXPECTING_NEIGHBORHOOD_RESULT,  // 期望邻域搜索结果// 期望局部优化步骤
            EXPECTING_LOCAL_REFINEMENT_RESULT, // 期望局部优化结果
            EXPECTING_SHAKE_RESULT,         // 期望shake结果
            LOG_END               // 期望总结部分
        }
        ParseState state = ParseState.EXPECTING_METADATA;
        SolveCycle currentCycle = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {

            while ((line = reader.readLine()) != null) {
                lineCount++;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (state == ParseState.EXPECTING_METADATA) {
                    Matcher metadataMatcher = INSTANCE_PATTERN.matcher(line);
                    if (metadataMatcher.find()) {
                        LogSummary summary = new LogSummary();
                        summary.setInstanceName(metadataMatcher.group(1));
                        summary.setMethod(metadataMatcher.group(2));
                        parsedLog.setSummary(summary);
                        state = ParseState.EXPECTING_INITIAL_SOLUTION;
                    } else {
                        throw new IllegalStateException("Unexpected line in " + state.name() + " state: " + line);
                    }
                    continue;
                }

                if (state == ParseState.EXPECTING_INITIAL_SOLUTION) {
                    Matcher attemptMatcher = ATTEMPT_PATTERN.matcher(line);
                    Matcher noInitialSolutionMatcher = NO_INITIAL_SOLUTION_PATTERN.matcher(line);
                    Matcher reachedMatcher = MAXIMAL_SHAKE_ATTEMPTS_PATTERN.matcher(line);
                    Matcher summaryHeadMatcher = SUMMARY_HEAD_PATTERN.matcher(line);

                    if (attemptMatcher.find()) {
                        currentCycle = new SolveCycle();
                        TemporarySolution solution = new TemporarySolution(attemptMatcher.group(3));
                        currentCycle.setInitialSolutionElapsedTime(Double.parseDouble(attemptMatcher.group(4)));
                        currentCycle.setInitialSolutionEvaluatedSolutions(Integer.parseInt(attemptMatcher.group(5)));
                        currentCycle.setInitialSolution(solution);
                        state = ParseState.EXPECTING_NS_LR_S;
                    } else if (noInitialSolutionMatcher.find()) {
                        // 处理 "No Initial Solution Found by Heuristic" 情况
                        double elapsedTime = Double.parseDouble(noInitialSolutionMatcher.group(2));
                        int evaluatedSolutions = Integer.parseInt(noInitialSolutionMatcher.group(3));

                        state = ParseState.EXPECTING_INITIAL_SOLUTION;
                    } else if (reachedMatcher.find()) {
                        SolveCycle previousCycle = cycles.getLast();
                        previousCycle.setShakeNewPriorityTimes(Integer.parseInt(reachedMatcher.group(1)));
                        state = ParseState.EXPECTING_INITIAL_SOLUTION;
                    } else if (summaryHeadMatcher.find()) {
                        StringBuilder remainingLines = new StringBuilder();
                        while ((line = reader.readLine()) != null) {
                            lineCount++;
                            remainingLines.append(line).append("\n");
                        }
                        line = remainingLines.toString();
                        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(line);
                        if (summaryMatcher.find()) {
                            TemporarySolution initialSolution = new TemporarySolution(summaryMatcher.group(1));
                            TemporarySolution bestSolution = new TemporarySolution(summaryMatcher.group(2));
                            double improvementPercentage = Double.parseDouble(summaryMatcher.group(3));
                            double runningTime = Double.parseDouble(summaryMatcher.group(12));
                            parsedLog.getSummary().setBestSolution(bestSolution);
                            parsedLog.getSummary().setImprovementPercentage(improvementPercentage);
                            parsedLog.getSummary().setTotalTime(runningTime);
                        } else
                            throw new IllegalStateException("Unexpected line in " + state.name() + " state: " + line);
                        state = ParseState.LOG_END;
                    } else {
                        throw new IllegalStateException("Unexpected line in " + state.name() + " state: " + line);
                    }
                    continue;
                }

                if (state == ParseState.EXPECTING_NEIGHBORHOOD_RESULT ||
                        state == ParseState.EXPECTING_NS_LR_S) {
                    Matcher neighborCountMatcher = NEIGHBOR_COUNT_PATTERN.matcher(line);
                    Matcher neighborIterationMatcher = NEIGHBOR_ITERATION_PATTERN.matcher(line);
                    Matcher neighborhoodEndMatcher = NEIGHBOR_END_PATTERN.matcher(line);
                    Matcher noFeasibleSolutionMatcher = NO_FEASIBLE_SOLUTION_PATTERN.matcher(line);

                    if (neighborCountMatcher.find()) { // 这是邻域计数行，可以记录或忽略
                        int iteration = Integer.parseInt(neighborCountMatcher.group(1));
                        int neighborCount = Integer.parseInt(neighborCountMatcher.group(2));
//                        System.out.println("Iteration " + iteration + " will explore " + neighborCount + " neighbors");
                        state = ParseState.EXPECTING_NEIGHBORHOOD_RESULT;
                        continue;
                    } else if (neighborIterationMatcher.find()) {
                        int iteration = Integer.parseInt(neighborIterationMatcher.group(1));
                        String improvementType = neighborIterationMatcher.group(2);
                        TemporarySolution solution = new TemporarySolution(neighborIterationMatcher.group(3));

                        double elapsedTime = Double.parseDouble(neighborIterationMatcher.group(4));
                        int evaluatedSolutions = Integer.parseInt(neighborIterationMatcher.group(5));

                        NeighborImprovement improvement = new NeighborImprovement(
                                iteration, improvementType, solution, elapsedTime, evaluatedSolutions);
                        currentCycle.getNeighborImprovements().add(improvement);
                        state = ParseState.EXPECTING_NEIGHBORHOOD_RESULT;
                        continue;
                    } else if (noFeasibleSolutionMatcher.find()) {
                        // 处理 "No Feasible Solution"行
                        int iteration = Integer.parseInt(noFeasibleSolutionMatcher.group(1));
                        int currentNeighbors = Integer.parseInt(noFeasibleSolutionMatcher.group(2));
                        int maxNeighbors = Integer.parseInt(noFeasibleSolutionMatcher.group(3));
                        // 可以选择记录这些信息，或者简单地继续处理
                        state = ParseState.EXPECTING_NEIGHBORHOOD_RESULT;
                        continue;
                    } else if (neighborhoodEndMatcher.find()) {
                        TemporarySolution solution = new TemporarySolution(neighborhoodEndMatcher.group(1));
                        currentCycle.setNeighborhoodSolution(solution);
                        line = reader.readLine();
                        lineCount++;
                        if (line != null) {
                            line = line.trim();
                            Matcher improveMatcher = NEIGHBOR_IMPROVE_PATTERN.matcher(line);
                            if (improveMatcher.find()) {
                                ;
                            } else {
                                throw new IllegalStateException("Unexpected line in " + state.name() + " state: " + line);
                            }
                        }
                        state = ParseState.EXPECTING_LR_S;
                        continue;
                    }
                }

                if (state == ParseState.EXPECTING_LOCAL_REFINEMENT_RESULT ||
                        state == ParseState.EXPECTING_NS_LR_S || state == ParseState.EXPECTING_LR_S) {
                    Matcher localRefinementIterationMatcher = LOCAL_REFINEMENT_ITERATION_PATTERN.matcher(line);
                    Matcher locaRefinementEndMatcher = LOCAL_REFINEMENT_END_PATTERN.matcher(line);

                    if (localRefinementIterationMatcher.find()) {
                        LocalRefinementStep step = new LocalRefinementStep();
                        step.setType(localRefinementIterationMatcher.group(1));
                        TemporarySolution solution = new TemporarySolution(localRefinementIterationMatcher.group(2));
                        step.setSolution(solution);
                        step.setElapsedTime(Double.parseDouble(localRefinementIterationMatcher.group(3)));
                        step.setEvaluatedSolutions(Integer.parseInt(localRefinementIterationMatcher.group(4)));
                        currentCycle.getLocalRefinementSteps().add(step);
                        state = ParseState.EXPECTING_LOCAL_REFINEMENT_RESULT;
                        continue;
                    } else if (locaRefinementEndMatcher.find()) {
                        TemporarySolution localRefinementResult = new TemporarySolution(locaRefinementEndMatcher.group(1));
                        currentCycle.setLocalRefinementResult(localRefinementResult);

                        line = reader.readLine();
                        lineCount++;
                        if (line != null) {
                            line = line.trim();
                            Matcher improveMatcher = LOCAL_REFINEMENT_IMPROVE_PATTERN.matcher(line);
                            if (improveMatcher.find()) {
                                ;
                            } else {
                                throw new IllegalStateException("Unexpected line in " + state.name() + " state: " + line);
                            }
                        }


                        state = ParseState.EXPECTING_SHAKE_RESULT;
                        continue;
                    }
                }
                if (state == ParseState.EXPECTING_SHAKE_RESULT ||
                        state == ParseState.EXPECTING_NS_LR_S || state == ParseState.EXPECTING_LR_S) {
                    Matcher shakeResultMatcher = SHAKE_PATTERN.matcher(line);
                    if (shakeResultMatcher.find()) {
                        currentCycle.setShakeNumber(Integer.parseInt(shakeResultMatcher.group(1)));
                        // 查找改进百分比
                        line = reader.readLine();
                        lineCount++;
                        if (line != null) {
                            line = line.trim();
                            Matcher improveMatcher = SHAKE_IMPROVE_PATTERN.matcher(line);
                            if (improveMatcher.find()) {
                                currentCycle.setImprovementPercentage(Double.parseDouble(improveMatcher.group(1)));
                                line = reader.readLine();
                                lineCount++;
                            }
                            // 如果没有找到改进百分比行，nextLine已经是下一行了，直接处理时间信息
                            if (line != null) {
                                line = line.trim();
                                Matcher timeMatcher = SHAKE_END_PATTERN.matcher(line);
                                if (timeMatcher.find()) {
                                    currentCycle.setElapsedTime(Double.parseDouble(timeMatcher.group(1)));
                                    currentCycle.setEvaluatedSolutions(Integer.parseInt(timeMatcher.group(2)));
                                } else {
                                    throw new IllegalStateException("Unexpected line in " + state.name() + " state: " + line);
                                }
                            }

                            line = reader.readLine();
                            lineCount++;
                            if (line != null) {
                                line = line.trim();
                                Matcher timeMatcher = SEPARATOR_PATTERN.matcher(line);
                                if (timeMatcher.find()) {
                                    ;
                                } else {
                                    throw new IllegalStateException("Unexpected line in " + state.name() + " state: " + line);
                                }
                            }
                        }


                        cycles.add(currentCycle);
                        currentCycle = null; // 重置当前循环
                        state = ParseState.EXPECTING_INITIAL_SOLUTION;
                        continue;
                    }
                }

                throw new IllegalStateException("Unexpected line in " + state.name() + " state: " + line);
            }


            parsedLog.setCycles(cycles);
        } catch (Exception e) {
            System.err.println("Error happen in file's line:" + lineCount);
            System.err.println("Line context: " + line);
            System.err.println("Current state: " + state);

            e.printStackTrace();
        }

        if (state != ParseState.LOG_END)
            throw new IllegalStateException("Parsing failed. Current state: " + state);

        return parsedLog;
    }

    /**
     * 根据实例名和方法名查找对应的log文件
     *
     * @param instanceName 实例名，如 {04-01-01}_{06-02}_01
     * @param methodName   方法名，如 decomposedNeighborhoodSearch
     * @return 完整的文件名，如果未找到则返回null
     */
    public static String findLogFileByInstanceAndMethod(String directory, String instanceName, String methodName,
                                                        LocalDateTime startTime, LocalDateTime endTime, boolean latestOnly) {
        try {
            Path logDir = Paths.get(directory);

            if (!Files.exists(logDir)) {
                System.err.println("Log directory does not exist: " + logDir.toAbsolutePath());
                return null;
            }

            String pattern = "config_" + instanceName + "_" + methodName;
            Pattern fileNamePattern = Pattern.compile("config_" + Pattern.quote(instanceName) + "_" + Pattern.quote(methodName) + "_(\\d{8}T\\d{9})\\.log");

            // 查找匹配的文件
            List<Path> matchingFiles = Files.list(logDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileNamePattern.matcher(fileName).matches();
                    })
                    .filter(path -> {
                        // 从文件名中提取时间并检查时间范围
                        String fileName = path.getFileName().toString();
                        Matcher matcher = fileNamePattern.matcher(fileName);
                        if (matcher.matches()) {
                            String timeString = matcher.group(1); // 提取时间戳部分
                            LocalDateTime fileTime = LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS"));

                            boolean afterStart = (startTime == null) || !fileTime.isBefore(startTime);
                            boolean beforeEnd = (endTime == null) || !fileTime.isAfter(endTime);
                            return afterStart && beforeEnd;
                        }
                        return false;
                    })
                    .sorted((p1, p2) -> {
                        // 按文件名中的时间戳排序，最新的在前
                        Matcher m1 = fileNamePattern.matcher(p1.getFileName().toString());
                        Matcher m2 = fileNamePattern.matcher(p2.getFileName().toString());
                        if (m1.matches() && m2.matches()) {
                            String timeString1 = m1.group(1);
                            String timeString2 = m2.group(1);
                            LocalDateTime time1 = LocalDateTime.parse(timeString1, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS"));
                            LocalDateTime time2 = LocalDateTime.parse(timeString2, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS"));
                            return time2.compareTo(time1); // 降序排列，最新的在前
                        }
                        return 0;
                    })
                    .toList();

            if (!matchingFiles.isEmpty()) {
                if (latestOnly) {
                    // 只返回最新的文件
                    return matchingFiles.getFirst().getFileName().toString();
                } else {
                    // 返回第一个匹配的文件（不考虑是否最新）
                    return matchingFiles.getFirst().getFileName().toString();
                }
            } else {
                System.err.println("No matching log file found for pattern: " + pattern);
                if (startTime != null || endTime != null) {
                    System.err.println("Time range: " + startTime + " to " + endTime);
                }

                // 列出目录中的所有匹配文件及其时间以帮助调试
                System.err.println("Available matching files in directory:");
                Files.list(logDir)
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            return fileNamePattern.matcher(fileName).matches();
                        })
                        .forEach(path -> {
                            String fileName = path.getFileName().toString();
                            Matcher matcher = fileNamePattern.matcher(fileName);
                            if (matcher.matches()) {
                                String timeString = matcher.group(1);
                                LocalDateTime fileTime = LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS"));
                                System.err.println("  " + fileName + " - Time: " + fileTime);
                            }
                        });
            }
        } catch (Exception e) {
            System.err.println("Error searching for log file: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    public static void extractInitialSolutionsFromAllLogs() {
        File outputDir = new File("linux/logLargeSimple");

        if (!outputDir.exists() || !outputDir.isDirectory()) {
            System.err.println("Output directory does not exist: " + outputDir.getAbsolutePath());
            return;
        }

        // 打印表头
        System.out.println("文件名,instance,method,obj,route,time,congestion,elapsed time,evaluated solutions");

        String[] targetMethods = {"decomposedNeighborhoodSearch", "decomposedRandom", "localRefinement"};

        // 遍历目录中的所有.log文件
        File[] logFiles = outputDir.listFiles((dir, name) -> {
            if (!name.endsWith(".log")) {
                return false;
            }

            // 检查文件名是否包含目标方法名
            for (String method : targetMethods) {
                if (name.contains("_" + method + "_")) {
                    return true;
                }
            }
            return false;
        });
        if (logFiles == null) {
            System.err.println("Failed to list files in directory: " + outputDir.getAbsolutePath());
            return;
        }

        for (File logFile : logFiles) {
            try {
                // 解析日志文件
                ParsedLog parsedLog = parseLogFile(logFile.getAbsolutePath());
                LogSummary summary = parsedLog.getSummary();
                List<SolveCycle> cycles = parsedLog.getCycles();

                if (cycles != null && !cycles.isEmpty()) {
                    SolveCycle firstCycle = cycles.getFirst();
                    TemporarySolution initialSolution = firstCycle.getInitialSolution();

                    if (initialSolution != null) {
                        // 输出CSV格式数据
                        System.out.printf("%s,%s,%s,%.8f,%.2f,%.2f,%.2f,%.2f,%d%n",
                                logFile.getName(),
                                summary.getInstanceName(),
                                summary.getMethod(),
                                initialSolution.getObjective(),
                                initialSolution.getRoute(),
                                initialSolution.getTime(),
                                initialSolution.getCongestion(),
                                firstCycle.getInitialSolutionElapsedTime(),
                                firstCycle.getInitialSolutionEvaluatedSolutions());
                    } else {
                        System.err.println("No initial solution found in file: " + logFile.getName());
                    }
                } else {
                    System.err.println("No solve cycles found in file: " + logFile.getName());
                }
            } catch (Exception e) {
                System.err.println("Error processing file: " + logFile.getName());
                e.printStackTrace();
            }
        }
    }


    public static void main(String[] args) {
        extractInitialSolutionsFromAllLogs();
        System.exit(0);
        String directory = "linux/newLog/";
        String fileName = findLogFileByInstanceAndMethod(directory,
                "{04-00-02}_{06-02}_03",
                "decomposedNeighborhoodSearch",
                // decomposedNeighborhoodSearch, decomposedRandom, localRefinement
                LocalDateTime.of(2025, 8, 30, 0, 0),
                null,
                true);
        System.out.println("File Name: " + fileName);
        ParsedLog parsedLog = parseLogFile(directory + fileName);
        double stopRunTime = 820.4;
        boolean printedFirstAfterStopTime = false;

        LogSummary summary = parsedLog.getSummary();
        System.out.println("Instance: " + summary.getInstanceName());
        System.out.println("Method: " + summary.getMethod());
        System.out.println("Improvement Percentage: " + summary.getImprovementPercentage());
        System.out.println("Total Time: " + summary.getTotalTime());
        System.out.println("Total Evaluated Solutions: " + summary.getTotalEvaluatedSolutions());
        System.out.println("Initial Solution: " + summary.getInitialSolution());
        System.out.println("Best Solution: " + summary.getBestSolution());
        System.out.println("shake, improve?, evaluated, obj, objRoute, objTime, objCongestion, elapsed time");

        for (SolveCycle cycle : parsedLog.getCycles()) {
            int shake = cycle.getShakeNumber();

            System.out.printf("%d, ***, %d, %.2f, %.2f, %.2f, %.2f, %.2f\n",
                    shake,
                    cycle.getInitialSolutionEvaluatedSolutions(),
                    cycle.getInitialSolution().getObjective(),
                    cycle.getInitialSolution().getRoute(),
                    cycle.getInitialSolution().getTime(),
                    cycle.getInitialSolution().getCongestion(),
                    cycle.getInitialSolutionElapsedTime());
            if (cycle.getInitialSolutionElapsedTime() >= stopRunTime) {
                printedFirstAfterStopTime = true;
            }
            if (printedFirstAfterStopTime) {
                break;
            }
            for (NeighborImprovement neighbor : cycle.getNeighborImprovements()) {
                System.out.printf("%d, %s, %d, %.2f, %.2f, %.2f, %.2f, %.2f\n",
                        shake,
                        neighbor.getImprovementType(),
                        neighbor.getEvaluatedSolutions(),
                        neighbor.getSolution().getObjective(),
                        neighbor.getSolution().getRoute(),
                        neighbor.getSolution().getTime(),
                        neighbor.getSolution().getCongestion(),
                        neighbor.getElapsedTime());
                if (neighbor.getElapsedTime() >= stopRunTime) {
                    printedFirstAfterStopTime = true;
                }
                if (printedFirstAfterStopTime) {
                    break;
                }
            }
            if (printedFirstAfterStopTime) {
                break;
            }
            for (LocalRefinementStep step : cycle.getLocalRefinementSteps()) {
                System.out.printf("%d, %s, %d, %.2f, %.2f, %.2f, %.2f, %.2f\n",
                        shake,
                        step.getType(),
                        step.getEvaluatedSolutions(),
                        step.getSolution().getObjective(),
                        step.getSolution().getRoute(),
                        step.getSolution().getTime(),
                        step.getSolution().getCongestion(),
                        step.getElapsedTime());
                if (step.getElapsedTime() >= stopRunTime) {
                    printedFirstAfterStopTime = true;
                }
                if (printedFirstAfterStopTime) {
                    break;
                }
            }
            if (printedFirstAfterStopTime) {
                break;
            }
        }


    }
}


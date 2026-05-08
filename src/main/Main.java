package main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Main {
    static {
        System.setProperty("logback.configurationFile",
                System.getProperty("logback.configurationFile", "logback.xml"));
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final Set<Process> ACTIVE_CHILD_PROCESSES = ConcurrentHashMap.newKeySet();
    private static volatile boolean childCleanupHookInstalled = false;

    public static void main(String[] args) {
        Params params = Params.parse(args);
        launchBatch(params);
    }

    public static void launchBatch(Params params) {
        if (params.experiments == null || params.experiments.isEmpty()) {
            throw new IllegalArgumentException("No experiments to launch.");
        }
        installChildCleanupHook();

        int workerThreads = params.experiments.stream()
                .map(p -> p.cplexThreads)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(1);
        int defaultProcesses = Math.max(1, Runtime.getRuntime().availableProcessors() / Math.max(workerThreads, 1));
        int processCount = Math.min(params.experiments.size(),
                params.parallelConfigs != null ? params.parallelConfigs : defaultProcesses);

        LOGGER.info("Launch experiments={}, parallel_configs={}, heap={}",
                params.experiments.size(), processCount,
                params.heapMb == null ? "default" : params.heapMb + "m");

        ExecutorService executor = Executors.newFixedThreadPool(processCount);
        CompletionService<BatchRunResult> completionService = new ExecutorCompletionService<>(executor);
        int submitted = 0;
        int completed = 0;

        try {
            while (submitted < processCount && submitted < params.experiments.size()) {
                int next = submitted;
                completionService.submit(() -> runChildJvm(params, params.experiments.get(next)));
                submitted++;
            }

            while (completed < submitted) {
                Future<BatchRunResult> future = completionService.take();
                BatchRunResult result = future.get();
                completed++;
                String progress = String.format("Completed %d/%d: %s",
                        completed, params.experiments.size(), result.summaryLine());
                if (result.exitCode == 0) {
                    LOGGER.info(progress);
                } else {
                    LOGGER.error(progress);
                }

                if (submitted < params.experiments.size()) {
                    int next = submitted;
                    completionService.submit(() -> runChildJvm(params, params.experiments.get(next)));
                    submitted++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Launcher interrupted.");
        } catch (ExecutionException e) {
            LOGGER.error("Child task failed before producing a result.", e);
        } finally {
            executor.shutdownNow();
            destroyActiveChildProcesses();
        }
    }

    private static BatchRunResult runChildJvm(Params launcherParams, Experiment experiment) {
        LocalDateTime start = LocalDateTime.now();
        List<String> command = buildChildCommand(launcherParams, experiment);
        LOGGER.info("Start {}", experiment.briefName());
        LOGGER.debug("Child command for {}: {}", experiment.briefName(), String.join(" ", command));

        int exitCode = -1;
        String error = "";
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(System.getProperty("user.dir")));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = builder.start();
            ACTIVE_CHILD_PROCESSES.add(process);
            try {
                exitCode = waitForChildProcess(process);
            } finally {
                ACTIVE_CHILD_PROCESSES.remove(process);
            }
        } catch (IOException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        }

        LocalDateTime end = LocalDateTime.now();
        return new BatchRunResult(experiment.briefName(), exitCode, start, end, error);
    }

    private static List<String> buildChildCommand(Params launcherParams, Experiment experiment) {
        List<String> command = new ArrayList<>();
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator +
                (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");

        command.add(javaExe);
        command.addAll(inheritableJvmOptions());
        if (launcherParams.heapMb != null) {
            command.add("-Xmx" + launcherParams.heapMb + "m");
        }
        String libraryPath = System.getProperty("java.library.path");
        if (libraryPath != null && !libraryPath.isBlank()) {
            command.add("-Djava.library.path=" + libraryPath);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Runner.class.getName());
        command.add("parent_pid=" + ProcessHandle.current().pid());
        command.addAll(experiment.toWorkerArgs());
        return command;
    }

    private static List<String> inheritableJvmOptions() {
        List<String> options = new ArrayList<>();
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (shouldInheritJvmOption(option)) {
                options.add(option);
            }
        }
        return options;
    }

    private static boolean shouldInheritJvmOption(String option) {
        String lower = option.toLowerCase();
        return !lower.startsWith("-xmx")
                && !lower.startsWith("-xms")
                && !lower.startsWith("-xss")
                && !lower.startsWith("-xx:maxheapsize")
                && !lower.startsWith("-xx:initialheapsize")
                && !lower.startsWith("-agentlib")
                && !lower.startsWith("-agentpath")
                && !lower.startsWith("-javaagent")
                && !lower.startsWith("-djava.library.path=")
                && !lower.startsWith("-xrun");
    }

    private static int waitForChildProcess(Process process) throws InterruptedException {
        while (true) {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                return process.exitValue();
            }
            if (Thread.currentThread().isInterrupted()) {
                destroyProcessTree(process);
                throw new InterruptedException("Interrupted while waiting for child JVM.");
            }
        }
    }

    private static synchronized void installChildCleanupHook() {
        if (childCleanupHookInstalled) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Main::destroyActiveChildProcesses, "apjor-child-cleanup"));
        childCleanupHookInstalled = true;
    }

    private static void destroyActiveChildProcesses() {
        for (Process process : ACTIVE_CHILD_PROCESSES) {
            destroyProcessTree(process);
        }
    }

    private static void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();

        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                handle.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        }
    }

    private static class BatchRunResult {
        final String experiment;
        final int exitCode;
        final LocalDateTime start;
        final LocalDateTime end;
        final String error;

        BatchRunResult(String experiment, int exitCode,
                       LocalDateTime start, LocalDateTime end, String error) {
            this.experiment = experiment;
            this.exitCode = exitCode;
            this.start = start;
            this.end = end;
            this.error = error;
        }

        String summaryLine() {
            return String.format("Done %s exit=%d runningTime=%.2fs%s",
                    experiment, exitCode,
                    Duration.between(start, end).toMillis() / 1000.0,
                    error == null || error.isBlank() ? "" : " error=" + error);
        }
    }
}

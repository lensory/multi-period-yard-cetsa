package main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.RunContext;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    static {
        System.setProperty("logback.configurationFile",
                System.getProperty("logback.configurationFile", "logback.xml"));
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_SHUTDOWN_GRACE_PERIOD_SECONDS = 120;
    private static final String WORKER_LOGBACK_CONFIGURATION = "logback-worker.xml";
    private static final Map<Process, ChildProcessInfo> ACTIVE_CHILD_PROCESSES = new ConcurrentHashMap<>();
    private static final AtomicBoolean CHILD_CLEANUP_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean LAUNCHER_PARENT_SHUTDOWN_REQUESTED = new AtomicBoolean(false);
    private static volatile boolean childCleanupHookInstalled = false;
    private static volatile long shutdownGracePeriodSeconds = DEFAULT_SHUTDOWN_GRACE_PERIOD_SECONDS;

    public static void main(String[] args) {
        Params params = Params.parse(args);
        startLauncherParentWatcher();
        launchBatch(params);
    }

    public static void launchBatch(Params params) {
        if (params.experiments == null || params.experiments.isEmpty()) {
            throw new IllegalArgumentException("No experiments to launch.");
        }
        shutdownGracePeriodSeconds = params.shutdownGracePeriodSeconds == null
                ? DEFAULT_SHUTDOWN_GRACE_PERIOD_SECONDS
                : params.shutdownGracePeriodSeconds;
        installChildCleanupHook();

        int workerThreads = firstConfiguredWorkerThreads(params);
        int defaultProcesses = Math.max(1, Runtime.getRuntime().availableProcessors() / Math.max(workerThreads, 1));
        int maxParallel = Math.min(params.experiments.size(),
                params.parallelConfigs != null ? params.parallelConfigs : defaultProcesses);
        ResourceBudget resourceBudget = new ResourceBudget(params.threadBudget, params.memoryBudgetMb, maxParallel);
        validateResourceBudget(params, resourceBudget);

        LOGGER.info("Launch experiments={}, parallel_configs={}, thread_budget={}, memory_budget={}, default_heap={}, shutdown_grace={}s",
                params.experiments.size(), maxParallel,
                params.threadBudget == null ? "none" : params.threadBudget,
                memoryMb(params.memoryBudgetMb, "none"),
                memoryMb(params.heapMb, "default"),
                shutdownGracePeriodSeconds);

        ExecutorService executor = Executors.newFixedThreadPool(maxParallel);
        CompletionService<BatchRunResult> completionService = new ExecutorCompletionService<>(executor);
        int submitted = 0;
        int completed = 0;
        int running = 0;

        try {
            while (completed < params.experiments.size()) {
                boolean launched = false;
                while (submitted < params.experiments.size() && running < maxParallel) {
                    Experiment experiment = params.experiments.get(submitted);
                    ResourceAllocation allocation = ResourceAllocation.from(params, experiment);
                    if (!resourceBudget.canReserve(allocation)) {
                        break;
                    }

                    resourceBudget.reserve(allocation);
                    int next = submitted;
                    completionService.submit(() -> runChildJvm(params, params.experiments.get(next), allocation));
                    submitted++;
                    running++;
                    launched = true;
                    LOGGER.info("Submitted {}/{}: {} resources={} active={}",
                            submitted, params.experiments.size(), experiment.briefName(),
                            allocation.summary(), resourceBudget.summary());
                }

                if (completed >= params.experiments.size()) {
                    break;
                }
                if (running == 0 && submitted < params.experiments.size()) {
                    Experiment blocked = params.experiments.get(submitted);
                    ResourceAllocation allocation = ResourceAllocation.from(params, blocked);
                    throw new IllegalArgumentException("Experiment " + blocked.briefName()
                            + " cannot fit launcher budgets: required " + allocation.summary()
                            + ", limits " + resourceBudget.limitsSummary());
                }
                if (launched && submitted < params.experiments.size() && running < maxParallel) {
                    continue;
                }

                Future<BatchRunResult> future = completionService.take();
                BatchRunResult result = future.get();
                resourceBudget.release(result.allocation);
                completed++;
                running--;
                String progress = String.format("Completed %d/%d: %s",
                        completed, params.experiments.size(), result.summaryLine());
                if (result.exitCode == 0) {
                    LOGGER.info(progress);
                } else {
                    LOGGER.error(progress);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Launcher interrupted.");
        } catch (ExecutionException e) {
            LOGGER.error("Child task failed before producing a result.", e);
        } finally {
            executor.shutdownNow();
            shutdownActiveChildProcesses("Launcher batch ended", shutdownGracePeriodSeconds);
        }
    }

    private static int firstConfiguredWorkerThreads(Params params) {
        return params.experiments.stream()
                .map(p -> p.cplexThreads)
                .filter(value -> value != null && value > 0)
                .findFirst()
                .orElse(1);
    }

    private static void validateResourceBudget(Params params, ResourceBudget resourceBudget) {
        for (Experiment experiment : params.experiments) {
            ResourceAllocation allocation = ResourceAllocation.from(params, experiment);
            if (!resourceBudget.fitsLimits(allocation)) {
                throw new IllegalArgumentException("Experiment " + experiment.briefName()
                        + " cannot fit launcher budgets: required " + allocation.summary()
                        + ", limits " + resourceBudget.limitsSummary());
            }
        }
    }

    private static BatchRunResult runChildJvm(Params launcherParams, Experiment experiment, ResourceAllocation allocation) {
        LocalDateTime start = LocalDateTime.now();
        LOGGER.info("Start {} resources={}", experiment.briefName(), allocation.summary());

        int exitCode = -1;
        String error = "";
        File runDir = Runner.runOutputDir(experiment, start);
        Process process = null;
        ChildProcessInfo childInfo = null;
        try {
            if (!runDir.exists() && !runDir.mkdirs()) {
                throw new IOException("Cannot create experiment output directory: " + runDir);
            }

            Experiment workerExperiment = experiment.copySettings();
            workerExperiment.setInstanceKey(experiment.small, experiment.medium, experiment.large,
                    experiment.rows, experiment.cols, experiment.seed);
            workerExperiment.runOutputDir = runDir.getPath();

            List<String> command = buildChildCommand(launcherParams, workerExperiment);
            LOGGER.debug("Child command for {}: {}", experiment.briefName(), String.join(" ", command));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(System.getProperty("user.dir")));
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(runDir, "worker.out.log")));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(new File(runDir, "worker.err.log")));

            process = builder.start();
            childInfo = new ChildProcessInfo(process, experiment.briefName(), runDir);
            ACTIVE_CHILD_PROCESSES.put(process, childInfo);
            try {
                exitCode = waitForChildProcess(process);
            } finally {
                ACTIVE_CHILD_PROCESSES.remove(process);
            }
        } catch (IOException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            error = "Interrupted";
            if (process != null && childInfo != null && process.isAlive()) {
                exitCode = stopChildProcessWithGrace(childInfo, "Launcher worker interrupted", shutdownGracePeriodSeconds);
            }
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        LocalDateTime end = LocalDateTime.now();
        return new BatchRunResult(experiment.briefName(), exitCode, start, end, error, runDir, allocation);
    }

    private static List<String> buildChildCommand(Params launcherParams, Experiment experiment) {
        List<String> command = new ArrayList<>();
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator +
                (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");

        command.add(javaExe);
        command.addAll(inheritableJvmOptions());
        command.add("-Dlogback.configurationFile=" + WORKER_LOGBACK_CONFIGURATION);
        Integer heapMb = experiment.heapMb != null ? experiment.heapMb : launcherParams.heapMb;
        if (heapMb != null) {
            command.add("-Xmx" + heapMb + "m");
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

    private static String memoryMb(Integer valueMb, String defaultValue) {
        return valueMb == null ? defaultValue : valueMb + "m";
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
                && !lower.startsWith("-dlogback.configurationfile=")
                && !lower.startsWith("-xrun");
    }

    private static int waitForChildProcess(Process process) throws InterruptedException {
        while (true) {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                return process.exitValue();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while waiting for child JVM.");
            }
        }
    }

    private static synchronized void installChildCleanupHook() {
        if (childCleanupHookInstalled) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shutdownActiveChildProcesses("JVM shutdown", shutdownGracePeriodSeconds),
                "apjor-child-cleanup"));
        childCleanupHookInstalled = true;
    }

    private static void startLauncherParentWatcher() {
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        if (parent == null) {
            return;
        }

        LOGGER.info("Watching launcher parent process pid={} ({})", parent.pid(), processDescription(parent));
        Thread watcher = new Thread(() -> {
            while (true) {
                if (!parent.isAlive()) {
                    if (LAUNCHER_PARENT_SHUTDOWN_REQUESTED.compareAndSet(false, true)) {
                        LOGGER.error("Launcher parent process {} is gone. Stopping child JVMs gracefully.", parent.pid());
                        shutdownActiveChildProcesses("Launcher parent process is gone", shutdownGracePeriodSeconds);
                    }
                    System.exit(130);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "apjor-maven-parent-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static String processDescription(ProcessHandle process) {
        ProcessHandle.Info info = process.info();
        String command = info.command().orElse("");
        String commandLine = info.commandLine().orElse("");
        String arguments = info.arguments().map(args -> String.join(" ", args)).orElse("");
        String description = (command + " " + commandLine + " " + arguments).trim();
        return description.isBlank() ? "unknown" : description;
    }

    private static void shutdownActiveChildProcesses(String reason, long graceSeconds) {
        List<ChildProcessInfo> children = List.copyOf(ACTIVE_CHILD_PROCESSES.values());
        if (children.isEmpty()) {
            return;
        }
        if (!CHILD_CLEANUP_RUNNING.compareAndSet(false, true)) {
            LOGGER.debug("Child cleanup already running; skip duplicate request: {}", reason);
            return;
        }

        try {
            LOGGER.warn("Requesting graceful shutdown for {} child process(es), grace={}s, reason={}",
                    children.size(), graceSeconds, reason);
            for (ChildProcessInfo child : children) {
                requestChildStop(child, reason);
            }

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds);
            for (ChildProcessInfo child : children) {
                Integer exitCode = waitForExitUntil(child.process, deadlineNanos);
                if (exitCode != null) {
                    LOGGER.info("Child exited after graceful stop: {} pid={} exit={}",
                            child.experiment, child.pid, exitCode);
                }
            }

            for (ChildProcessInfo child : children) {
                if (child.process.isAlive()) {
                    LOGGER.error("Child did not exit within {}s; destroying process tree: {} pid={} output={}",
                            graceSeconds, child.experiment, child.pid, child.runDir.getPath());
                    destroyProcessTree(child.process);
                }
            }
        } finally {
            CHILD_CLEANUP_RUNNING.set(false);
        }
    }

    private static int stopChildProcessWithGrace(ChildProcessInfo child, String reason, long graceSeconds) {
        requestChildStop(child, reason);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(graceSeconds);
        Integer exitCode = waitForExitUntil(child.process, deadlineNanos);
        if (exitCode != null) {
            return exitCode;
        }
        LOGGER.error("Child did not exit within {}s; destroying process tree: {} pid={} output={}",
                graceSeconds, child.experiment, child.pid, child.runDir.getPath());
        destroyProcessTree(child.process);
        return child.process.isAlive() ? -1 : child.process.exitValue();
    }

    private static void requestChildStop(ChildProcessInfo child, String reason) {
        if (!child.stopRequested.compareAndSet(false, true)) {
            return;
        }

        File stopFile = new File(child.runDir, RunContext.STOP_REQUESTED_FILE);
        String content = String.format(
                "requested_at=%s%nreason=%s%nparent_pid=%d%nchild_pid=%d%nexperiment=%s%n",
                LocalDateTime.now(), reason, ProcessHandle.current().pid(), child.pid, child.experiment);
        try {
            Files.createDirectories(child.runDir.toPath());
            Files.writeString(stopFile.toPath(), content, StandardCharsets.UTF_8);
            LOGGER.warn("Wrote graceful stop request for {} pid={} file={}",
                    child.experiment, child.pid, stopFile.getPath());
        } catch (IOException e) {
            LOGGER.warn("Failed to write graceful stop request for {} pid={} file={}: {}",
                    child.experiment, child.pid, stopFile.getPath(), e.getMessage());
        }
    }

    private static Integer waitForExitUntil(Process process, long deadlineNanos) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            while (process.isAlive()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return null;
                }
                long waitMillis = Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 1000L);
                waitMillis = Math.max(waitMillis, 1L);
                try {
                    if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                        return process.exitValue();
                    }
                } catch (InterruptedException e) {
                    restoreInterrupt = true;
                }
            }
            return process.exitValue();
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
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

    private static class ChildProcessInfo {
        final Process process;
        final long pid;
        final String experiment;
        final File runDir;
        final AtomicBoolean stopRequested = new AtomicBoolean(false);

        ChildProcessInfo(Process process, String experiment, File runDir) {
            this.process = process;
            this.pid = process.pid();
            this.experiment = experiment;
            this.runDir = runDir;
        }
    }

    private static class BatchRunResult {
        final String experiment;
        final int exitCode;
        final LocalDateTime start;
        final LocalDateTime end;
        final String error;
        final File runDir;
        final ResourceAllocation allocation;

        BatchRunResult(String experiment, int exitCode,
                       LocalDateTime start, LocalDateTime end, String error, File runDir,
                       ResourceAllocation allocation) {
            this.experiment = experiment;
            this.exitCode = exitCode;
            this.start = start;
            this.end = end;
            this.error = error;
            this.runDir = runDir;
            this.allocation = allocation;
        }

        String summaryLine() {
            return String.format("Done %s exit=%d runningTime=%.2fs output=%s%s",
                    experiment, exitCode,
                    Duration.between(start, end).toMillis() / 1000.0,
                    runDir.getPath(),
                    error == null || error.isBlank() ? "" : " error=" + error);
        }
    }

    private static class ResourceBudget {
        private final Integer threadLimit;
        private final Integer memoryLimitMb;
        private final int parallelLimit;
        private int usedThreads = 0;
        private int usedMemoryMb = 0;

        ResourceBudget(Integer threadLimit, Integer memoryLimitMb, int parallelLimit) {
            this.threadLimit = threadLimit;
            this.memoryLimitMb = memoryLimitMb;
            this.parallelLimit = parallelLimit;
        }

        boolean canReserve(ResourceAllocation allocation) {
            return fitsLimits(allocation)
                    && (threadLimit == null || usedThreads + allocation.threads <= threadLimit)
                    && (memoryLimitMb == null || usedMemoryMb + allocation.memoryMb <= memoryLimitMb);
        }

        boolean fitsLimits(ResourceAllocation allocation) {
            return (threadLimit == null || allocation.threads <= threadLimit)
                    && (memoryLimitMb == null || allocation.memoryMb <= memoryLimitMb);
        }

        void reserve(ResourceAllocation allocation) {
            usedThreads += allocation.threads;
            usedMemoryMb += allocation.memoryMb;
        }

        void release(ResourceAllocation allocation) {
            usedThreads -= allocation.threads;
            usedMemoryMb -= allocation.memoryMb;
        }

        String summary() {
            return String.format("parallel_limit=%d, threads=%d/%s, memory=%d/%s MB",
                    parallelLimit,
                    usedThreads, threadLimit == null ? "none" : threadLimit.toString(),
                    usedMemoryMb, memoryLimitMb == null ? "none" : memoryLimitMb.toString());
        }

        String limitsSummary() {
            return String.format("parallel_limit=%d, thread_budget=%s, memory_budget=%s",
                    parallelLimit,
                    threadLimit == null ? "none" : threadLimit.toString(),
                    memoryLimitMb == null ? "none" : memoryLimitMb + "m");
        }
    }

    private record ResourceAllocation(int threads, int memoryMb, Integer heapMb) {
        static ResourceAllocation from(Params params, Experiment experiment) {
            int threads = experiment.cplexThreads == null ? 1 : experiment.cplexThreads;
            Integer heapMb = experiment.heapMb != null ? experiment.heapMb : params.heapMb;
            int memoryMb;
            if (experiment.memoryBudgetMb != null) {
                memoryMb = experiment.memoryBudgetMb;
            } else {
                memoryMb = Math.max(
                        experiment.rssLimitMb == null ? 0 : experiment.rssLimitMb,
                        heapMb == null ? 0 : heapMb
                );
            }
            return new ResourceAllocation(threads, memoryMb, heapMb);
        }

        String summary() {
            return String.format("threads=%d, memory_budget=%dm, heap=%s",
                    threads, memoryMb, heapMb == null ? "default" : heapMb + "m");
        }
    }
}

package util;

import ilog.concert.IloException;
import ilog.cplex.IloCplex;
import main.Experiment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class CplexSession implements AutoCloseable {
    private static final Logger CPLEX_LOGGER = LoggerFactory.getLogger("cplex");
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int LOG_PATTERN_OVERHEAD_BYTES = 128;

    private final RunContext context;
    private IloCplex.Aborter aborter;
    private IloCplex cplex;
    private boolean closed = false;

    public CplexSession(Experiment experiment, RunContext context) throws IloException {
        this.context = context;

        boolean success = false;

        try {
            this.cplex = new IloCplex();
            CplexLogLimiter logLimiter = CplexLogLimiter.from(experiment, context);
            this.cplex.setOut(cplexLogStream(CPLEX_LOGGER, LogLevel.INFO, logLimiter));
            this.cplex.setWarning(cplexLogStream(CPLEX_LOGGER, LogLevel.WARN, logLimiter));
            this.aborter = new IloCplex.Aborter();
            this.cplex.use(this.aborter);

            if (experiment.cplexThreads != null)
                cplex.setParam(IloCplex.Param.Threads, experiment.cplexThreads);

            if (context != null && Double.isFinite(context.remainingSeconds())) {
                double remaining = context.remainingSeconds();
                if (remaining > 0.0) {
                    cplex.setParam(IloCplex.Param.TimeLimit, remaining);
                }
            } else if (experiment.timeLimit != null) {
                cplex.setParam(IloCplex.Param.TimeLimit, experiment.timeLimit.doubleValue());
            }

            if (experiment.workMemMb != null)
                cplex.setParam(IloCplex.Param.WorkMem, experiment.workMemMb.doubleValue());
            if (experiment.nodeFile != null)
                cplex.setParam(IloCplex.Param.MIP.Strategy.File, experiment.nodeFile);
            if (experiment.treeMemMb != null)
                cplex.setParam(IloCplex.Param.MIP.Limits.TreeMemory, experiment.treeMemMb.doubleValue());
            if (experiment.workDir != null && !experiment.workDir.isBlank()) {
                File dir = new File(experiment.workDir);
                if (!dir.exists() && !dir.mkdirs())
                    throw new RuntimeException("Cannot create CPLEX workdir: " + experiment.workDir);
                cplex.setParam(IloCplex.Param.WorkDir, experiment.workDir);
            }

            cplex.setParam(IloCplex.Param.Emphasis.Memory, experiment.memoryEmphasis);

            if (experiment.mipDisplay != null)
                cplex.setParam(IloCplex.Param.MIP.Display, experiment.mipDisplay);
            if (experiment.simplexDisplay != null)
                cplex.setParam(IloCplex.Param.Simplex.Display, experiment.simplexDisplay);
            if (experiment.barrierDisplay != null)
                cplex.setParam(IloCplex.Param.Barrier.Display, experiment.barrierDisplay);


            if (experiment.mipEmphasis != null)
                switch (experiment.mipEmphasis) {
                    case "feasibility" -> cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.Feasibility);
                    case "best_bound", "bound" ->
                            cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.BestBound);
                    case "optimality" -> cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.Optimality);
                    case "balanced" -> cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.Balanced);
                    default -> throw new IllegalArgumentException("Unknown mip_emphasis: " + experiment.mipEmphasis);
                }


            if (context != null) {
                context.registerAborter(this.aborter);
            }

            success = true;
        } finally {
            if (!success && this.cplex != null) {
                if (context != null) {
                    context.unregisterAborter(this.aborter);
                }
                this.cplex.end();
            }
        }
    }

    public IloCplex cplex() {
        return cplex;
    }

    public IloCplex getCplex() {
        return cplex;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (context != null) {
            context.unregisterAborter(aborter);
        }

        cplex.end();
    }

    private static PrintStream cplexLogStream(Logger logger, LogLevel level, CplexLogLimiter limiter) {
        return new PrintStream(new LoggerOutputStream(logger, level, limiter), true, StandardCharsets.UTF_8);
    }

    private enum LogLevel {
        INFO,
        WARN
    }

    private static final class LoggerOutputStream extends OutputStream {
        private final Logger logger;
        private final LogLevel level;
        private final CplexLogLimiter limiter;
        private final Map<String, String> mdcContext;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);

        private LoggerOutputStream(Logger logger, LogLevel level, CplexLogLimiter limiter) {
            this.logger = logger;
            this.level = level;
            this.limiter = limiter;
            this.mdcContext = MDC.getCopyOfContextMap();
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                flushBuffer();
                return;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(bytes[off + i]);
            }
        }

        @Override
        public synchronized void flush() {
            flushBuffer();
        }

        private void flushBuffer() {
            if (buffer.size() == 0) {
                return;
            }
            String line = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            log(line);
        }

        private void log(String line) {
            if (limiter != null && !limiter.tryReserve(line)) {
                String notice = limiter.suppressionNotice();
                if (notice != null) {
                    logInternal(notice, LogLevel.WARN);
                }
                return;
            }
            logInternal(line, level);
        }

        private void logInternal(String line, LogLevel targetLevel) {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (mdcContext == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(mdcContext);
                }
                if (targetLevel == LogLevel.WARN) {
                    logger.warn(line);
                } else {
                    logger.info(line);
                }
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        }
    }

    private static final class CplexLogLimiter {
        private final long limitBytes;
        private final RunContext context;
        private final AtomicLong reservedBytes = new AtomicLong(0L);
        private final AtomicBoolean noticeEmitted = new AtomicBoolean(false);

        private CplexLogLimiter(long limitBytes, RunContext context) {
            this.limitBytes = limitBytes;
            this.context = context;
        }

        private static CplexLogLimiter from(Experiment experiment, RunContext context) {
            if (context != null) {
                return new CplexLogLimiter(-1L, context);
            }
            if (experiment.cplexLogLimitMb == null) {
                return null;
            }
            return new CplexLogLimiter(experiment.cplexLogLimitMb.longValue() * BYTES_PER_MB, null);
        }

        private boolean tryReserve(String line) {
            long estimatedBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L + LOG_PATTERN_OVERHEAD_BYTES;
            if (context != null) {
                return context.reserveCplexLogBytes(estimatedBytes);
            }
            while (true) {
                long current = reservedBytes.get();
                if (current + estimatedBytes > limitBytes) {
                    return false;
                }
                if (reservedBytes.compareAndSet(current, current + estimatedBytes)) {
                    return true;
                }
            }
        }

        private String suppressionNotice() {
            if (context != null) {
                return context.cplexLogSuppressionNotice();
            }
            if (!noticeEmitted.compareAndSet(false, true)) {
                return null;
            }
            long limitMb = Math.max(1L, limitBytes / BYTES_PER_MB);
            return "CPLEX log suppressed after reaching configured cplex_log_limit="
                    + limitMb + " MB. Solver continues; non-CPLEX logs are still written.";
        }
    }
}

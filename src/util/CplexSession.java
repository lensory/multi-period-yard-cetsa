package util;

import ilog.concert.IloException;
import ilog.cplex.IloCplex;
import main.Params;

import java.io.File;

public class CplexSession implements AutoCloseable {

    private final Params params;
    private final RunContext context;
    private IloCplex cplex;
    private boolean closed = false;

    public CplexSession(Params params, RunContext context) throws IloException {
        this.params = params;
        this.context = context;

        boolean success = false;

        try {
            this.cplex = new IloCplex();

            if (params.threads != null)
                cplex.setParam(IloCplex.Param.Threads, params.threads);

            if (context != null && Double.isFinite(context.remainingSeconds())) {
                double remaining = context.remainingSeconds();
                if (remaining > 0.0) {
                    cplex.setParam(IloCplex.Param.TimeLimit, remaining);
                }
            } else if (params.timeLimit != null) {
                cplex.setParam(IloCplex.Param.TimeLimit, params.timeLimit.doubleValue());
            }

            if (params.workMemMb != null)
                cplex.setParam(IloCplex.Param.WorkMem, params.workMemMb.doubleValue());
            if (params.nodeFile != null)
                cplex.setParam(IloCplex.Param.MIP.Strategy.File, params.nodeFile);
            if (params.treeMemMb != null)
                cplex.setParam(IloCplex.Param.MIP.Limits.TreeMemory, params.treeMemMb.doubleValue());
            if (params.workDir != null && !params.workDir.isBlank()) {
                File dir = new File(params.workDir);
                if (!dir.exists() && !dir.mkdirs())
                    throw new RuntimeException("Cannot create CPLEX workdir: " + params.workDir);
                cplex.setParam(IloCplex.Param.WorkDir, params.workDir);
            }

            cplex.setParam(IloCplex.Param.Emphasis.Memory, params.memoryEmphasis);

            if (params.mipDisplay != null)
                cplex.setParam(IloCplex.Param.MIP.Display, params.mipDisplay);


            if (params.mipEmphasis != null)
                switch (params.mipEmphasis) {
                    case "feasibility" -> cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.Feasibility);
                    case "best_bound", "bound" ->
                            cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.BestBound);
                    case "optimality" -> cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.Optimality);
                    case "balanced" -> cplex.setParam(IloCplex.Param.Emphasis.MIP, IloCplex.MIPEmphasis.Balanced);
                    default -> throw new IllegalArgumentException("Unknown mip_emphasis: " + params.mipEmphasis);
                }


            if (context != null) {
                context.registerCplex(this.cplex);
            }

            success = true;
        } finally {
            if (!success && this.cplex != null) {
                if (context != null) {
                    context.unregisterCplex(this.cplex);
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
            context.unregisterCplex(cplex);
        }

        cplex.end();
    }
}

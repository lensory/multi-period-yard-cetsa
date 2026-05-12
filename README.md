# APJOR Yard

Code for container terminal yard template planning and loading/unloading scheduling with multiple vessel periods. Includes CPLEX-based exact models and several decomposition/search heuristics. Supports instance generation, solving, log parsing, and result summarization.

**Project Structure**
- `src/main` entry points, batch runs, log parsing, summarization tools
- `src/solver` CPLEX models and search algorithms
- `src/entity` domain entities and solution I/O/validation
- `src/dto` JSON/CSV data transfer objects
- `src/util` utilities
- `input/` instances (JSON, can be generated)
- `output/` experiment directories, each containing `experiment.log`, optional `model.lp`, and solution CSV files
- `log/` legacy run logs
- `linux/` legacy batch logs and statistics

**Dependencies**
- JDK 24 for project compilation. Maven can still be launched by the system JDK if `~/.m2/toolchains.xml` points to JDK 24.
- IBM ILOG CPLEX Java API installed in the local Maven repository.
- Maven dependencies: Jackson, SLF4J, Logback, and CPLEX.

Local CPLEX coordinates currently used by `pom.xml`:

```xml
<dependency>
  <groupId>com.ibm.ilog</groupId>
  <artifactId>cplex</artifactId>
  <version>12.10</version>
</dependency>
```

CPLEX 22.1.2 is also available locally and can be selected by changing `cplex.version` in `pom.xml`.

**Run**
- Compile:

```bash
mvn compile
```

- Run from Maven:

```bash
mvn exec:java -Dexec.args="parallel_configs=2 solver=local_refinement vessel=(2,0,1) rows=6 seed=1-5 write=false timelimit=3600 cplex_threads=4"
```

For CPLEX runs, the native library path must still be available at runtime, for example by keeping the CPLEX `bin/x64_win64` directory on `PATH`.

**Common Parameters (`main.Params`)**
- `solver`: solver type, supports `cplex`, `flow_cplex`, `sequential`, `decomposed`, `local_refinement`, etc.
- `vessel`/`vessels`: vessel-count tuples like `(2,0,1)` or `(2,0,1),(2,1,0)`
- `small`/`medium`/`large`: counts of small/medium/large vessels
- `rows`/`cols`: yard rows/cols (`cols` auto-computed if omitted)
- `seeds`: random seed ranges like `1-5,7,9-11`
- `timelimit`: time limit in seconds
- `cplex_threads`: CPLEX thread count
- `write`: write solutions (`true/false`)
- `parallel_configs`: number of concurrently running config experiments
- `shutdown_grace_period_seconds`: seconds to wait for worker JVMs to stop gracefully after launcher interrupt before force-kill (default `120`)

**Tools**
- `main.InstanceGenerator`: generate instance JSON
- `main.Summarizer`: summarize `output/` results to CSV
- `main.LogParser`/`main.CplexLogParser`: parse logs
- `main.Checker`: read solutions and validate with CPLEX

**Notes**
- `Runner` uses `InstanceGenerator` by default. To read `input/*.json`, switch in `Runner.readInstance`.

# APJOR Yard

Code for container terminal yard template planning and loading/unloading scheduling with multiple vessel periods. Includes CPLEX-based exact models and several decomposition/search heuristics. Supports instance generation, solving, log parsing, and result summarization.

**Project Structure**
- `src/main` entry points, batch runs, log parsing, summarization tools
- `src/solver` CPLEX models and search algorithms
- `src/entity` domain entities and solution I/O/validation
- `src/dto` JSON/CSV data transfer objects
- `src/util` utilities
- `input/` instances (JSON, can be generated)
- `output/` solutions (`solution_*`)
- `log/` run logs
- `linux/` legacy batch logs and statistics

**Dependencies**
- JDK 17+ (uses `Stream.toList`, etc.)
- IBM ILOG CPLEX (`cplex.jar`, `concert.jar`, etc. must be on the classpath)
- Bundled libs: Jackson + Commons CSV under `lib/`

**Run**
- Recommended: run `main.Runner` in your IDE with `lib/*.jar` and CPLEX jars on the classpath
- Command-line example (adjust CPLEX path to your installation)

```bash
javac -cp "lib/*;path/to/cplex/*" -d out $(git ls-files "src/**/*.java")
java -cp "out;lib/*;path/to/cplex/*" main.Runner 
parallel=true processes=2 solver=local_refinement vessel=(2,0,1) rows=6 seed=1-5 write=false timelimit=3600 threads=4
```

**Common Parameters (`main.Params`)**
- `solver`: solver type, supports `cplex`, `sequential`, `decomposed`, `local_refinement`, etc.
- `vessel`/`vessels`: vessel-count tuples like `(2,0,1)` or `(2,0,1),(2,1,0)`
- `small`/`medium`/`large`: counts of small/medium/large vessels
- `rows`/`cols`: yard rows/cols (`cols` auto-computed if omitted)
- `seeds`: random seed ranges like `1-5,7,9-11`
- `timelimit`: time limit in seconds
- `threads`: thread count
- `write`: write solutions (`true/false`)
- `parallel`, `processes`: parallel batch runs

**Tools**
- `main.InstanceGenerator`: generate instance JSON
- `main.Summarizer`: summarize `output/` results to CSV
- `main.LogParser`/`main.CplexLogParser`: parse logs
- `main.Checker`: read solutions and validate with CPLEX

**Notes**
- `Runner` uses `InstanceGenerator` by default. To read `input/*.json`, switch in `Runner.readInstance`.
